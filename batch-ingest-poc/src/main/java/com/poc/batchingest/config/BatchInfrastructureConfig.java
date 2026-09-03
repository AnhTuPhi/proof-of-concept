package com.poc.batchingest.config;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.configuration.support.JobRegistrySmartInitializingSingleton;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Wires the Spring Batch infrastructure (JobRepository, transaction manager, partition-worker executor).
 * Extending {@link DefaultBatchConfiguration} keeps us off the deprecated {@code @EnableBatchProcessing}
 * path that Spring Boot replaced in 3.x.
 */
@Configuration
@EnableConfigurationProperties(IngestProperties.class)
public class BatchInfrastructureConfig extends DefaultBatchConfiguration {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final IngestProperties props;

    public BatchInfrastructureConfig(DataSource dataSource,
                                     PlatformTransactionManager transactionManager,
                                     IngestProperties props) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.props = props;
    }

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }

    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    /**
     * Bounded executor that runs partition worker steps concurrently. Bounded queue +
     * caller-runs rejection policy gives back-pressure instead of unbounded memory growth.
     */
    @Bean(name = "partitionTaskExecutor")
    public TaskExecutor partitionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getThreadPool().getCore());
        executor.setMaxPoolSize(props.getThreadPool().getMax());
        executor.setQueueCapacity(props.getThreadPool().getQueue());
        executor.setThreadNamePrefix("batch-partition-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated launcher executor so the REST controller doesn't block on a job. One thread
     * per concurrent job — partition fan-out lives in {@link #partitionTaskExecutor()}.
     */
    @Bean(name = "jobLauncherTaskExecutor")
    public TaskExecutor jobLauncherTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("job-launcher-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    /**
     * Override the auto-configured sync launcher so {@code POST /jobs/{name}} can return
     * the execution id immediately instead of blocking until the job finishes.
     */
    @Bean
    @Primary
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(jobLauncherTaskExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }

    /**
     * Spring Boot 3.4 doesn't auto-wire a {@link JobOperator}; declare one so we can
     * stop / restart jobs from the REST controller.
     */
    @Bean
    public JobOperator jobOperator(JobRepository jobRepository,
                                   JobExplorer jobExplorer,
                                   JobRegistry jobRegistry,
                                   JobLauncher asyncJobLauncher) {
        SimpleJobOperator operator = new SimpleJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobExplorer(jobExplorer);
        operator.setJobRegistry(jobRegistry);
        operator.setJobLauncher(asyncJobLauncher);
        return operator;
    }

    /**
     * Auto-registers every {@code @Bean Job} into the {@link JobRegistry} so the operator
     * can look them up by name (needed for restart, which reads the job name from the
     * persisted execution).
     */
    @Bean
    public JobRegistrySmartInitializingSingleton jobRegistrar(JobRegistry jobRegistry) {
        JobRegistrySmartInitializingSingleton registrar = new JobRegistrySmartInitializingSingleton();
        registrar.setJobRegistry(jobRegistry);
        return registrar;
    }
}
