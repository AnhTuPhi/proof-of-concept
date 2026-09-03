package com.poc.batchingest.batch.listener;

import com.poc.batchingest.domain.TransactionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records rejected items into {@code ingest_errors} so operators can inspect what was dropped
 * after a step finishes with skips (instead of only seeing them in the log).
 */
@Component
@Slf4j
public class RejectedItemListener implements SkipListener<TransactionRecord, TransactionRecord> {

    private final JdbcTemplate jdbc;

    private String jobName = "unknown";
    private String stepName = "unknown";
    private String partitionId;

    public RejectedItemListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeStep
    public void captureStepContext(StepExecution stepExecution) {
        this.jobName = stepExecution.getJobExecution().getJobInstance().getJobName();
        this.stepName = stepExecution.getStepName();
        Object pid = stepExecution.getExecutionContext().get("partitionId");
        this.partitionId = pid != null ? pid.toString() : null;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        record(null, t, "read");
    }

    @Override
    public void onSkipInProcess(TransactionRecord item, Throwable t) {
        record(item, t, "process");
    }

    @Override
    public void onSkipInWrite(TransactionRecord item, Throwable t) {
        record(item, t, "write");
    }

    private void record(TransactionRecord item, Throwable t, String phase) {
        try {
            jdbc.update("""
                    INSERT INTO ingest_errors (job_name, step_name, partition_id, payload, error_class, error_msg)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    jobName,
                    stepName,
                    partitionId,
                    item == null ? null : item.toString(),
                    t.getClass().getName(),
                    "[" + phase + "] " + t.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Failed to persist skip record for job={} step={}: {}", jobName, stepName, ex.getMessage());
        }
    }
}
