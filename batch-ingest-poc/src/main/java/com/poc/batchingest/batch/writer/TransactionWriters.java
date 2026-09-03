package com.poc.batchingest.batch.writer;

import com.poc.batchingest.domain.TransactionRecord;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Idempotent upsert into {@code transactions} keyed on {@code transaction_id}. Using ON CONFLICT
 * here means a restarted job (or a re-played source file) won't duplicate rows.
 *
 * <p>If the deployment target is Oracle / SQL Server, swap the SQL for the platform's MERGE syntax.
 */
@Configuration
public class TransactionWriters {

    private static final String UPSERT_SQL = """
            INSERT INTO transactions
                (transaction_id, account_id, symbol, side, quantity, price, trade_ts, source)
            VALUES
                (:transactionId, :accountId, :symbol, :side, :quantity, :price, :tradeTs, :source)
            ON CONFLICT (transaction_id) DO NOTHING
            """;

    @Bean
    public JdbcBatchItemWriter<TransactionRecord> transactionWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<TransactionRecord>()
                .dataSource(dataSource)
                .sql(UPSERT_SQL)
                .beanMapped()
                .assertUpdates(false)   // ON CONFLICT DO NOTHING may legitimately update 0 rows
                .build();
    }
}
