package com.poc.batchingest.config;

import com.poc.batchingest.batch.listener.JobMetricsListener;
import com.poc.batchingest.batch.listener.RejectedItemListener;
import com.poc.batchingest.batch.partitioner.IdRangePartitioner;
import com.poc.batchingest.batch.processor.ValidatingTransactionProcessor;
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
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * DB-to-DB ingest: copy {@code source_transactions} → {@code transactions} with the same
 * chunk + partitioning machinery used by the file-based jobs. {@link IdRangePartitioner}
 * splits the id range so each worker reads a disjoint slice using a server-side cursor.
 */
@Configuration
public class DbJobConfig {

    private final IngestProperties props;
    private final ValidatingTransactionProcessor processor;
    private final JdbcBatchItemWriter<TransactionRecord> transactionWriter;
    private final JobMetricsListener jobMetricsListener;
    private final RejectedItemListener rejectedItemListener;
    private final TaskExecutor partitionTaskExecutor;

    public DbJobConfig(IngestProperties props,
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
    public Partitioner dbRangePartitioner(JdbcTemplate jdbcTemplate) {
        return new IdRangePartitioner(jdbcTemplate, props.getDbSource().getTable(), props.getDbSource().getIdColumn());
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<TransactionRecord> dbPartitionReader(
            DataSource dataSource,
            @Value("#{stepExecutionContext['minId']}") Long minId,
            @Value("#{stepExecutionContext['maxId']}") Long maxId) {

        PostgresPagingQueryProvider provider = new PostgresPagingQueryProvider();
        provider.setSelectClause("SELECT transaction_id, account_id, symbol, side, quantity, price, trade_ts");
        provider.setFromClause("FROM " + props.getDbSource().getTable());
        provider.setWhereClause("WHERE " + props.getDbSource().getIdColumn() + " BETWEEN :minId AND :maxId");
        Map<String, Order> sortKeys = new HashMap<>();
        sortKeys.put(props.getDbSource().getIdColumn(), Order.ASCENDING);
        provider.setSortKeys(sortKeys);

        Map<String, Object> params = new HashMap<>();
        params.put("minId", minId);
        params.put("maxId", maxId);

        return new JdbcPagingItemReaderBuilder<TransactionRecord>()
                .name("dbPartitionReader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(params)
                .pageSize(props.getDbSource().getFetchSize())
                .fetchSize(props.getDbSource().getFetchSize())
                .saveState(true)
                .rowMapper((rs, rowNum) -> TransactionRecord.builder()
                        .transactionId(rs.getString("transaction_id"))
                        .accountId(rs.getString("account_id"))
                        .symbol(rs.getString("symbol"))
                        .side(rs.getString("side"))
                        .quantity(rs.getBigDecimal("quantity"))
                        .price(rs.getBigDecimal("price"))
                        .tradeTs(rs.getTimestamp("trade_ts").toLocalDateTime())
                        .source("DB")
                        .build())
                .build();
    }

    @Bean
    public Step dbWorkerStep(JobRepository jobRepository,
                             PlatformTransactionManager txManager,
                             JdbcPagingItemReader<TransactionRecord> dbPartitionReader) {
        return new StepBuilder("dbWorkerStep", jobRepository)
                .<TransactionRecord, TransactionRecord>chunk(props.getChunkSize(), txManager)
                .reader(dbPartitionReader)
                .processor(processor)
                .writer(transactionWriter)
                .faultTolerant()
                .skip(ConstraintViolationException.class)
                .skipLimit(props.getSkipLimit())
                .listener(rejectedItemListener)
                .build();
    }

    @Bean
    public PartitionHandler dbPartitionHandler(Step dbWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(dbWorkerStep);
        handler.setGridSize(props.getGridSize());
        handler.setTaskExecutor(partitionTaskExecutor);
        return handler;
    }

    @Bean
    public Step dbManagerStep(JobRepository jobRepository,
                              Partitioner dbRangePartitioner,
                              PartitionHandler dbPartitionHandler) {
        return new StepBuilder("dbManagerStep", jobRepository)
                .partitioner("dbWorkerStep", dbRangePartitioner)
                .partitionHandler(dbPartitionHandler)
                .build();
    }

    @Bean
    public Job dbIngestJob(JobRepository jobRepository, Step dbManagerStep) {
        return new JobBuilder("dbIngestJob", jobRepository)
                .listener(jobMetricsListener)
                .start(dbManagerStep)
                .build();
    }
}
