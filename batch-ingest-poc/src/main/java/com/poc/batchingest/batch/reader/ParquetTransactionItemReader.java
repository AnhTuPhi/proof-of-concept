package com.poc.batchingest.batch.reader;

import com.poc.batchingest.domain.TransactionRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ExecutionContext;

import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Restartable Parquet reader. Each partition gets a comma-separated list of file URIs; we open
 * them sequentially with {@link AvroParquetReader}.
 *
 * <p>Restart strategy: we persist the index of the current file and the count of rows already read
 * from it. On {@link #open(ExecutionContext)} we fast-forward by skipping that many rows. This
 * mirrors how {@code FlatFileItemReader} handles restarts — coarser-grained than per-row marks,
 * but works without needing Parquet-internal row group offsets.
 */
@Slf4j
public class ParquetTransactionItemReader implements ItemStreamReader<TransactionRecord> {

    private static final String KEY_FILE_INDEX = "parquet.fileIndex";
    private static final String KEY_ROW_INDEX = "parquet.rowIndex";

    private final Deque<File> remainingFiles = new ArrayDeque<>();
    private final File[] allFiles;

    private int currentFileIndex = 0;
    private long rowsReadInCurrentFile = 0;

    private ParquetReader<GenericRecord> currentReader;
    private File currentFile;

    public ParquetTransactionItemReader(String fileUris) {
        this.allFiles = Arrays.stream(fileUris.split(","))
                .filter(s -> !s.isBlank())
                .map(uri -> new File(URI.create(uri.trim())))
                .toArray(File[]::new);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (executionContext.containsKey(KEY_FILE_INDEX)) {
            currentFileIndex = executionContext.getInt(KEY_FILE_INDEX);
        }
        long resumeAtRow = executionContext.containsKey(KEY_ROW_INDEX)
                ? executionContext.getLong(KEY_ROW_INDEX) : 0L;

        for (int i = currentFileIndex; i < allFiles.length; i++) {
            remainingFiles.addLast(allFiles[i]);
        }

        try {
            advanceToNextFile();
            for (long i = 0; currentReader != null && i < resumeAtRow; i++) {
                if (currentReader.read() == null) break;
                rowsReadInCurrentFile++;
            }
            log.info("ParquetReader resume: fileIndex={} skipRows={} totalFiles={}",
                    currentFileIndex, resumeAtRow, allFiles.length);
        } catch (Exception e) {
            throw new ItemStreamException("Failed to open Parquet stream", e);
        }
    }

    @Override
    public TransactionRecord read() {
        try {
            while (currentReader != null) {
                GenericRecord r = currentReader.read();
                if (r == null) {
                    closeCurrentReader();
                    advanceToNextFile();
                    rowsReadInCurrentFile = 0;
                    continue;
                }
                rowsReadInCurrentFile++;
                return toTransactionRecord(r);
            }
            return null;
        } catch (Exception e) {
            throw new ItemStreamException("Failed reading Parquet row", e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(KEY_FILE_INDEX, currentFileIndex);
        executionContext.putLong(KEY_ROW_INDEX, rowsReadInCurrentFile);
    }

    @Override
    public void close() throws ItemStreamException {
        closeCurrentReader();
    }

    private void advanceToNextFile() throws Exception {
        if (remainingFiles.isEmpty()) {
            currentReader = null;
            currentFile = null;
            return;
        }
        currentFile = remainingFiles.pollFirst();
        currentFileIndex = indexOf(currentFile);
        Configuration conf = new Configuration();
        currentReader = AvroParquetReader.<GenericRecord>builder(
                        new LocalInputFile(currentFile.toPath())
                ).withConf(conf)
                .build();
    }

    private int indexOf(File f) {
        for (int i = 0; i < allFiles.length; i++) if (allFiles[i].equals(f)) return i;
        return currentFileIndex;
    }

    private void closeCurrentReader() {
        if (currentReader != null) {
            try { currentReader.close(); } catch (Exception ignored) { }
            currentReader = null;
        }
    }

    private static TransactionRecord toTransactionRecord(GenericRecord r) {
        Object tsRaw = r.get("tradeTs");
        LocalDateTime ts;
        if (tsRaw instanceof Number n) {
            ts = LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
        } else {
            ts = LocalDateTime.parse(tsRaw.toString());
        }
        return TransactionRecord.builder()
                .transactionId(String.valueOf(r.get("transactionId")))
                .accountId(String.valueOf(r.get("accountId")))
                .symbol(String.valueOf(r.get("symbol")))
                .side(String.valueOf(r.get("side")))
                .quantity(new BigDecimal(String.valueOf(r.get("quantity"))))
                .price(new BigDecimal(String.valueOf(r.get("price"))))
                .tradeTs(ts)
                .source("PARQUET")
                .build();
    }
}
