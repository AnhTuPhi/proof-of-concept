package com.poc.batchingest;

import com.poc.batchingest.util.TestDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test: generate a small CSV, run the partitioned CSV job, assert rows landed.
 * Uses the {@code h2} profile so it runs without docker.
 */
@SpringBootTest
@SpringBatchTest
@ActiveProfiles("h2")
class CsvIngestJobIntegrationTest {

    @Autowired private JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired private TestDataGenerator generator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Job csvIngestJob;

    private Path tmpDir;

    @BeforeEach
    void prepare() throws Exception {
        tmpDir = Files.createTempDirectory("batch-ingest-csv-it-");
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM ingest_errors");
        generator.generateCsv(tmpDir.toString(), 4, 500);
    }

    @Test
    void csvJobIngestsAllValidRowsAndSkipsInvalidOnes() throws Exception {
        jobLauncherTestUtils.setJob(csvIngestJob);
        JobExecution exec = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addString("inputDir", tmpDir.toString())
                .addLong("launchedAt", System.currentTimeMillis())
                .toJobParameters());

        assertThat(exec.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        Integer ingested = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(ingested).isGreaterThan(0);

        long totalGenerated = 4L * 500L;
        long read = exec.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
        assertThat(read).isEqualTo(totalGenerated);

        long written = exec.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
        long skipped = exec.getStepExecutions().stream()
                .mapToLong(s -> s.getReadSkipCount() + s.getProcessSkipCount() + s.getWriteSkipCount()).sum();
        assertThat(written + skipped).isEqualTo(totalGenerated);
    }
}
