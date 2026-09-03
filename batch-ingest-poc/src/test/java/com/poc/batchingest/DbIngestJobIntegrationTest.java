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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds {@code source_transactions}, runs the DB-to-DB partitioned job, asserts the rows
 * arrive in {@code transactions}.
 */
@SpringBootTest
@SpringBatchTest
@ActiveProfiles("h2")
class DbIngestJobIntegrationTest {

    @Autowired private JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired private TestDataGenerator generator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Job dbIngestJob;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM source_transactions");
        jdbc.update("DELETE FROM ingest_errors");
        generator.seedDb(2_000L, 500);
    }

    @Test
    void dbJobCopiesSourceTableIntoTargetTable() throws Exception {
        jobLauncherTestUtils.setJob(dbIngestJob);
        JobExecution exec = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLong("launchedAt", System.currentTimeMillis())
                .toJobParameters());

        assertThat(exec.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        long read = exec.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
        assertThat(read).isEqualTo(2_000L);

        Integer ingested = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(ingested).isGreaterThan(0);
    }
}
