package com.example.espoc.bulk.controller;

import com.example.espoc.bulk.service.BulkBenchmarkService;
import com.example.espoc.bulk.service.BulkBenchmarkService.BenchmarkResult;
import com.example.espoc.bulk.service.BulkBenchmarkService.Strategy;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bulk")
public class BulkController {

    private final BulkBenchmarkService svc;

    public BulkController(BulkBenchmarkService svc) { this.svc = svc; }

    @PostMapping("/run")
    public BenchmarkResult run(@RequestParam Strategy strategy,
                               @RequestParam(defaultValue = "100000") long count,
                               @RequestParam(defaultValue = "4") int parallelism) throws IOException {
        return svc.run(strategy, count, parallelism);
    }

    @GetMapping("/results")
    public Map<Strategy, BenchmarkResult> results() { return svc.latest(); }

    @GetMapping("/settings/{strategy}")
    public Map<String, Object> settings(@PathVariable Strategy strategy) {
        return switch (strategy) {
            case SINGLE        -> Map.of("refreshInterval", "1s",  "replicas", "0", "batchSize", 1,    "threads", 1);
            case BULK_DEFAULT  -> Map.of("refreshInterval", "1s",  "replicas", "0", "batchSize", 1000, "threads", 1);
            case BULK_TUNED    -> Map.of("refreshInterval", "-1 during ingest, 1s after", "replicas", "0", "batchSize", 5000, "threads", 1);
            case BULK_PARALLEL -> Map.of("refreshInterval", "-1 during ingest, 1s after", "replicas", "0", "batchSize", 5000, "threads", "N (param)");
        };
    }
}
