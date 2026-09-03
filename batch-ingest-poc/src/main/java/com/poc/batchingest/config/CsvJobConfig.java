package com.poc.batchingest.config;

import com.poc.batchingest.batch.listener.JobMetricsListener;
import com.poc.batchingest.batch.listener.RejectedItemListener;
import com.poc.batchingest.batch.partitioner.FilePartitioner;
import com.poc.batchingest.batch.processor.ValidatingTransactionProcessor;
import com.poc.batchingest.batch.reader.CsvTransactionReaderFactory;
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
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The CSV ingest job is the canonical example: a manager step partitions the input directory and
 * dispatches one worker step per partition. Each worker runs an independent chunk-oriented step.
 *
 * <p>Restart semantics: stop the app mid-run, restart it, then re-launch with the same JobParameters;
 * Spring Batch resumes each partition from its persisted item position.
 */
@Configuration
public class CsvJobConfig {

    private final IngestProperties props;
    private final CsvTransactionReaderFactory csvReaderFactory;
    private final ValidatingTransactionProcessor processor;
    private final JdbcBatchItemWriter<TransactionRecord> transactionWriter;
    private final JobMetricsListener jobMetricsListener;
    private final RejectedItemListener rejectedItemListener;
    private final TaskExecutor partitionTaskExecutor;

    public CsvJobConfig(IngestProperties props,
                        CsvTransactionReaderFactory csvReaderFactory,
                        ValidatingTransactionProcessor processor,
                        JdbcBatchItemWriter<TransactionRecord> transactionWriter,
                        JobMetricsListener jobMetricsListener,
                        RejectedItemListener rejectedItemListener,
                        TaskExecutor partitionTaskExecutor) {
        this.props = props;
        this.csvReaderFactory = csvReaderFactory;
        this.processor = processor;
        this.transactionWriter = transactionWriter;
        this.jobMetricsListener = jobMetricsListener;
        this.rejectedItemListener = rejectedItemListener;
        this.partitionTaskExecutor = partitionTaskExecutor;
    }

    @Bean
    @JobScope
    public Partitioner csvFilePartitioner(@Value("#{jobParameters['inputDir'] ?: null}") String inputDirOverride) {
        String dir = inputDirOverride != null ? inputDirOverride : props.getCsv().getInputDir();
        return new FilePartitioner(dir, props.getCsv().getGlob());
    }

    @Bean
    @StepScope
    public ItemReader<TransactionRecord> csvPartitionReader(
            @Value("#{stepExecutionContext['files']}") String files) {
        return csvReaderFactory.build(files, props.getCsv().isSkipHeader(), props.getCsv().getEncoding());
    }

    @Bean
    public Step csvWorkerStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager,
                              ItemReader<TransactionRecord> csvPartitionReader) {
        return new StepBuilder("csvWorkerStep", jobRepository)
                .<TransactionRecord, TransactionRecord>chunk(props.getChunkSize(), txManager)
                .reader(csvPartitionReader)
                .processor(processor)
                .writer(transactionWriter)
                .faultTolerant()
                .skip(ConstraintViolationException.class)
                .skip(NumberFormatException.class)
                .skip(java.time.format.DateTimeParseException.class)
                .skipLimit(props.getSkipLimit())
                .listener(rejectedItemListener)
                .build();
    }

    @Bean
    public PartitionHandler csvPartitionHandler(Step csvWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(csvWorkerStep);
        handler.setGridSize(props.getGridSize());
        handler.setTaskExecutor(partitionTaskExecutor);
        return handler;
    }

    @Bean
    public Step csvManagerStep(JobRepository jobRepository,
                               Partitioner csvFilePartitioner,
                               PartitionHandler csvPartitionHandler) {
        return new StepBuilder("csvManagerStep", jobRepository)
                .partitioner("csvWorkerStep", csvFilePartitioner)
                .partitionHandler(csvPartitionHandler)
                .build();
    }

    @Bean
    public Job csvIngestJob(JobRepository jobRepository, Step csvManagerStep) {
        return new JobBuilder("csvIngestJob", jobRepository)
                .listener(jobMetricsListener)
                .start(csvManagerStep)
                .build();
    }
}
