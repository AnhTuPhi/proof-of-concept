package com.poc.batchingest.batch.listener;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * Emits Micrometer metrics for every job run. Pair with {@code /actuator/prometheus}
 * to scrape job duration / status histograms.
 */
@Component
@Slf4j
public class JobMetricsListener implements JobExecutionListener {

    private final MeterRegistry registry;

    public JobMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("[job-start] name={} id={} params={}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        Duration duration = computeDuration(jobExecution);

        long read = jobExecution.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
        long written = jobExecution.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
        long skipped = jobExecution.getStepExecutions().stream()
                .mapToLong(s -> s.getReadSkipCount() + s.getProcessSkipCount() + s.getWriteSkipCount()).sum();

        Timer.builder("batch.job.duration")
                .tag("job", jobName)
                .tag("status", jobExecution.getStatus().name())
                .register(registry)
                .record(duration.toMillis(), TimeUnit.MILLISECONDS);

        registry.counter("batch.job.records.read", "job", jobName).increment(read);
        registry.counter("batch.job.records.written", "job", jobName).increment(written);
        registry.counter("batch.job.records.skipped", "job", jobName).increment(skipped);

        log.info("[job-end]   name={} id={} status={} duration={}ms read={} written={} skipped={}",
                jobName,
                jobExecution.getId(),
                jobExecution.getStatus(),
                duration.toMillis(),
                read, written, skipped);
    }

    private Duration computeDuration(JobExecution jobExecution) {
        LocalDateTime start = jobExecution.getStartTime();
        LocalDateTime end = jobExecution.getEndTime();
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(
                start.atZone(ZoneId.systemDefault()).toInstant(),
                end.atZone(ZoneId.systemDefault()).toInstant());
    }
}
