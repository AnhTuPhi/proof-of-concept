package com.poc.batchingest.batch.reader;

import com.poc.batchingest.domain.TransactionRecord;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.MultiResourceItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.builder.MultiResourceItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Builds a step-scoped CSV reader for a partition. The partition gives us a comma-separated list
 * of file URIs (set by {@link com.poc.batchingest.batch.partitioner.FilePartitioner}); we wrap them
 * with {@link MultiResourceItemReader} so a single worker can consume multiple files sequentially.
 *
 * <p>Restartability comes from {@link FlatFileItemReader} tracking the line number and
 * {@link MultiResourceItemReader} tracking the current resource index — both are persisted
 * by Spring Batch in {@code BATCH_STEP_EXECUTION_CONTEXT}.
 */
@Component
public class CsvTransactionReaderFactory {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public MultiResourceItemReader<TransactionRecord> build(String fileUris,
                                                            boolean skipHeader,
                                                            String encoding) {
        Resource[] resources = Arrays.stream(fileUris.split(","))
                .filter(s -> !s.isBlank())
                .map(uri -> new FileSystemResource(new File(URI.create(uri.trim()))))
                .toArray(Resource[]::new);

        FlatFileItemReader<TransactionRecord> delegate = new FlatFileItemReaderBuilder<TransactionRecord>()
                .name("csvDelegateReader")
                .encoding(encoding)
                .linesToSkip(skipHeader ? 1 : 0)
                .delimited()
                .delimiter(",")
                .names("transactionId", "accountId", "symbol", "side", "quantity", "price", "tradeTs")
                .fieldSetMapper(fs -> TransactionRecord.builder()
                        .transactionId(fs.readString("transactionId"))
                        .accountId(fs.readString("accountId"))
                        .symbol(fs.readString("symbol"))
                        .side(fs.readString("side"))
                        .quantity(new BigDecimal(fs.readString("quantity")))
                        .price(new BigDecimal(fs.readString("price")))
                        .tradeTs(LocalDateTime.parse(fs.readString("tradeTs"), TS_FMT))
                        .source("CSV")
                        .build())
                .strict(true)
                .build();

        return new MultiResourceItemReaderBuilder<TransactionRecord>()
                .name("csvMultiResourceReader")
                .resources(resources)
                .delegate(delegate)
                .saveState(true)
                .build();
    }
}
