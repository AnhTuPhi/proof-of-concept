package com.poc.batchingest.controller;

import com.poc.batchingest.config.IngestProperties;
import com.poc.batchingest.util.TestDataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Convenience endpoints for creating synthetic ingest data so the POC can be exercised
 * end-to-end without external sources. Defaults to a few hundred thousand rows; cap your
 * own request size if you point this at production hardware.
 */
@RestController
@RequestMapping("/data")
@Slf4j
public class DataGenController {

    private final TestDataGenerator generator;
    private final IngestProperties props;

    public DataGenController(TestDataGenerator generator, IngestProperties props) {
        this.generator = generator;
        this.props = props;
    }

    @PostMapping("/csv")
    public ResponseEntity<Map<String, Object>> csv(
            @RequestParam(defaultValue = "8") int files,
            @RequestParam(defaultValue = "100000") long rowsPerFile) throws Exception {
        long start = System.currentTimeMillis();
        generator.generateCsv(props.getCsv().getInputDir(), files, rowsPerFile);
        return ResponseEntity.ok(Map.of(
                "files", files,
                "rowsPerFile", rowsPerFile,
                "totalRows", (long) files * rowsPerFile,
                "dir", props.getCsv().getInputDir(),
                "tookMs", System.currentTimeMillis() - start));
    }

    @PostMapping("/parquet")
    public ResponseEntity<Map<String, Object>> parquet(
            @RequestParam(defaultValue = "4") int files,
            @RequestParam(defaultValue = "100000") long rowsPerFile) throws Exception {
        long start = System.currentTimeMillis();
        generator.generateParquet(props.getParquet().getInputDir(), files, rowsPerFile);
        return ResponseEntity.ok(Map.of(
                "files", files,
                "rowsPerFile", rowsPerFile,
                "totalRows", (long) files * rowsPerFile,
                "dir", props.getParquet().getInputDir(),
                "tookMs", System.currentTimeMillis() - start));
    }

    @PostMapping("/db")
    public ResponseEntity<Map<String, Object>> db(
            @RequestParam(defaultValue = "500000") long rows,
            @RequestParam(defaultValue = "5000") int batchSize) {
        long start = System.currentTimeMillis();
        generator.seedDb(rows, batchSize);
        return ResponseEntity.ok(Map.of(
                "totalRows", rows,
                "batchSize", batchSize,
                "tookMs", System.currentTimeMillis() - start));
    }
}
