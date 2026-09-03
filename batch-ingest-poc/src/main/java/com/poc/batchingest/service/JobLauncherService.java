package com.poc.batchingest.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Thin facade in front of Spring Batch's launcher / operator that the controller can call.
 * Adds a {@code launchedAt} parameter so successive runs of the same job get distinct
 * {@code JobInstance}s — Spring Batch refuses to re-run a completed instance otherwise.
 */
@Service
@Slf4j
public class JobLauncherService {

    private final JobLauncher jobLauncher;
    private final JobOperator jobOperator;
    private final JobExplorer jobExplorer;
    private final ApplicationContext applicationContext;

    public JobLauncherService(JobLauncher jobLauncher,
                              JobOperator jobOperator,
                              JobExplorer jobExplorer,
                              ApplicationContext applicationContext) {
        this.jobLauncher = jobLauncher;
        this.jobOperator = jobOperator;
        this.jobExplorer = jobExplorer;
        this.applicationContext = applicationContext;
    }

    public JobExecution launch(String jobName, Map<String, String> params)
            throws JobExecutionAlreadyRunningException, JobRestartException,
            JobInstanceAlreadyCompleteException, JobParametersInvalidException {

        Job job = applicationContext.getBean(jobName, Job.class);
        JobParametersBuilder builder = new JobParametersBuilder();
        builder.addLong("launchedAt", Instant.now().toEpochMilli());
        if (params != null) {
            params.forEach(builder::addString);
        }
        JobParameters jobParameters = builder.toJobParameters();
        log.info("Launching {} with params={}", jobName, jobParameters);
        return jobLauncher.run(job, jobParameters);
    }

    public Long restart(long executionId) throws Exception {
        log.info("Restarting executionId={}", executionId);
        return jobOperator.restart(executionId);
    }

    public boolean stop(long executionId) throws NoSuchJobExecutionException, JobExecutionNotRunningException {
        log.info("Stopping executionId={}", executionId);
        return jobOperator.stop(executionId);
    }

    public JobExecution status(long executionId) {
        return jobExplorer.getJobExecution(executionId);
    }
}
