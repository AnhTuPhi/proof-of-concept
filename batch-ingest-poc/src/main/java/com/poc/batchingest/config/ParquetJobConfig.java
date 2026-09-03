package com.poc.batchingest.config;

import com.poc.batchingest.batch.listener.JobMetricsListener;
import com.poc.batchingest.batch.listener.RejectedItemListener;
import com.poc.batchingest.batch.partitioner.FilePartitioner;
import com.poc.batchingest.batch.processor.ValidatingTransactionProcessor;
import com.poc.batchingest.batch.reader.ParquetTransactionItemReader;
import com.poc.batchingest.domain.TransactionRecord;
import jakarta.validation.ConstraintViolationException;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ParquetJobConfig {

    private final IngestProperties props;
    private final ValidatingTransactionProcessor processor;
    private final JdbcBatchItemWriter<TransactionRecord> transactionWriter;
    private final JobMetricsListener jobMetricsListener;
    private final RejectedItemListener rejectedItemListener;
    private final TaskExecutor partitionTaskExecutor;

    public ParquetJobConfig(IngestProperties props,
                            ValidatingTransactionProcessor processor,
                            JdbcBatchItemWriter<TransactionRecord> transactionWriter,
                            JobMetricsListener jobMetricsListener,
                            RejectedItemListener rejectedItemListener,
                            TaskExecutor partitionTaskExecutor) {
        this.props = props;
        this.processor = processor;
        this.transactionWriter = transactionWriter;
        this.jobMetricsListener = jobMetricsListener;
        this.rejectedItemListener = rejectedItemListener;
        this.partitionTaskExecutor = partitionTaskExecutor;
    }

    @Bean
    @JobScope
    public Partitioner parquetFilePartitioner(@Value("#{jobParameters['inputDir'] ?: null}") String inputDirOverride) {
        String dir = inputDirOverride != null ? inputDirOverride : props.getParquet().getInputDir();
        return new FilePartitioner(dir, props.getParquet().getGlob());
    }

    @Bean
    @StepScope
    public ItemStreamReader<TransactionRecord> parquetPartitionReader(
            @Value("#{stepExecutionContext['files']}") String files) {
        return new ParquetTransactionItemReader(files);
    }

    @Bean
    public Step parquetWorkerStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager,
                                  ItemStreamReader<TransactionRecord> parquetPartitionReader) {
        return new StepBuilder("parquetWorkerStep", jobRepository)
                .<TransactionRecord, TransactionRecord>chunk(props.getChunkSize(), txManager)
                .reader(parquetPartitionReader)
                .processor(processor)
                .writer(transactionWriter)
                .faultTolerant()
                .skip(ConstraintViolationException.class)
                .skip(IllegalArgumentException.class)
                .skipLimit(props.getSkipLimit())
                .listener(rejectedItemListener)
                .build();
    }

    @Bean
    public PartitionHandler parquetPartitionHandler(Step parquetWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(parquetWorkerStep);
        handler.setGridSize(props.getGridSize());
        handler.setTaskExecutor(partitionTaskExecutor);
        return handler;
    }

    @Bean
    public Step parquetManagerStep(JobRepository jobRepository,
                                   Partitioner parquetFilePartitioner,
                                   PartitionHandler parquetPartitionHandler) {
        return new StepBuilder("parquetManagerStep", jobRepository)
                .partitioner("parquetWorkerStep", parquetFilePartitioner)
                .partitionHandler(parquetPartitionHandler)
                .build();
    }

    @Bean
    public Job parquetIngestJob(JobRepository jobRepository, Step parquetManagerStep) {
        return new JobBuilder("parquetIngestJob", jobRepository)
                .listener(jobMetricsListener)
                .start(parquetManagerStep)
                .build();
    }
}
