package com.poc.batchingest.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates synthetic transactions in CSV, Parquet, and DB form. Optimised for "make millions
 * of rows fast" — streaming writers, no materialised lists, batched JDBC inserts.
 *
 * <p>Roughly 10% of generated rows are intentionally invalid (bad side, negative price, empty
 * symbol) so skip-policies and the rejected-item listener have something to exercise.
 */
@Component
@Slf4j
public class TestDataGenerator {

    private static final String[] SYMBOLS = {"VND", "VNM", "FPT", "HPG", "VIC", "VHM", "MSN", "MWG", "CTG", "BID"};
    private static final String[] SIDES = {"BUY", "SELL"};

    private final JdbcTemplate jdbc;

    public TestDataGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void generateCsv(String dir, int fileCount, long rowsPerFile) throws IOException {
        java.nio.file.Path base = Paths.get(dir);
        Files.createDirectories(base);
        for (int f = 0; f < fileCount; f++) {
            java.nio.file.Path file = base.resolve(String.format("transactions-%03d.csv", f));
            try (BufferedWriter w = Files.newBufferedWriter(file)) {
                w.write("transactionId,accountId,symbol,side,quantity,price,tradeTs\n");
                for (long i = 0; i < rowsPerFile; i++) {
                    w.write(toCsvLine(syntheticTuple(f, i)));
                    w.write("\n");
                }
            }
            log.info("Wrote {} rows to {}", rowsPerFile, file);
        }
    }

    public void generateParquet(String dir, int fileCount, long rowsPerFile) throws IOException {
        java.nio.file.Path base = Paths.get(dir);
        Files.createDirectories(base);
        Schema schema = parquetSchema();
        Configuration conf = new Configuration();
        for (int f = 0; f < fileCount; f++) {
            java.nio.file.Path file = base.resolve(String.format("transactions-%03d.parquet", f));
            try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(
                            new LocalOutputFile(file))
                    .withSchema(schema)
                    .withConf(conf)
                    .withCompressionCodec(CompressionCodecName.SNAPPY)
                    .withRowGroupSize(64L * 1024L * 1024L)
                    .withPageSize(1024 * 1024)
                    .withDictionaryEncoding(true)
                    .build()) {
                for (long i = 0; i < rowsPerFile; i++) {
                    writer.write(toAvro(schema, syntheticTuple(f, i)));
                }
            }
            log.info("Wrote {} parquet rows to {}", rowsPerFile, file);
        }
    }

    public void seedDb(long totalRows, int batchSize) {
        String sql = """
                INSERT INTO source_transactions
                    (transaction_id, account_id, symbol, side, quantity, price, trade_ts)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        long inserted = 0;
        while (inserted < totalRows) {
            int n = (int) Math.min(batchSize, totalRows - inserted);
            List<Object[]> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Tuple t = syntheticTuple(0, inserted + i);
                batch.add(new Object[]{
                        t.transactionId, t.accountId, t.symbol, t.side,
                        t.quantity, t.price, java.sql.Timestamp.valueOf(t.tradeTs)
                });
            }
            jdbc.batchUpdate(sql, batch);
            inserted += n;
            if (inserted % (batchSize * 10L) == 0) {
                log.info("DB seed progress: {}/{}", inserted, totalRows);
            }
        }
        log.info("DB seed complete: {} rows in {}", inserted, "source_transactions");
    }

    private static Schema parquetSchema() {
        return SchemaBuilder.record("Transaction").namespace("com.poc.batchingest")
                .fields()
                .name("transactionId").type().stringType().noDefault()
                .name("accountId").type().stringType().noDefault()
                .name("symbol").type().stringType().noDefault()
                .name("side").type().stringType().noDefault()
                .name("quantity").type().stringType().noDefault()
                .name("price").type().stringType().noDefault()
                .name("tradeTs").type().stringType().noDefault()
                .endRecord();
    }

    private static GenericRecord toAvro(Schema schema, Tuple t) {
        GenericRecord r = new GenericData.Record(schema);
        r.put("transactionId", t.transactionId);
        r.put("accountId", t.accountId);
        r.put("symbol", t.symbol);
        r.put("side", t.side);
        r.put("quantity", t.quantity.toPlainString());
        r.put("price", t.price.toPlainString());
        r.put("tradeTs", t.tradeTs.toString());
        return r;
    }

    private static String toCsvLine(Tuple t) {
        return t.transactionId + "," + t.accountId + "," + t.symbol + "," + t.side + ","
                + t.quantity.toPlainString() + "," + t.price.toPlainString() + "," + t.tradeTs;
    }

    private static Tuple syntheticTuple(int fileIdx, long row) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean bad = (row % 11 == 0); // ~9% rejected
        String txId = "TX-" + fileIdx + "-" + row + "-" + UUID.randomUUID().toString().substring(0, 8);
        String accountId = "ACC-" + (r.nextInt(100_000));
        String symbol = bad && row % 22 == 0 ? "" : SYMBOLS[r.nextInt(SYMBOLS.length)];
        String side = bad && row % 33 == 0 ? "FOO" : SIDES[r.nextInt(SIDES.length)];
        BigDecimal qty = bad && row % 44 == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(r.nextInt(1, 1000)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal price = bad && row % 55 == 0
                ? new BigDecimal("-1.0")
                : BigDecimal.valueOf(r.nextDouble(1.0, 10_000.0)).setScale(4, RoundingMode.HALF_UP);
        LocalDateTime ts = LocalDateTime.now()
                .minusSeconds(r.nextInt(0, 365 * 24 * 60 * 60));
        return new Tuple(txId, accountId, symbol, side, qty, price, ts);
    }

    private record Tuple(String transactionId, String accountId, String symbol, String side,
                         BigDecimal quantity, BigDecimal price, LocalDateTime tradeTs) {}
}
