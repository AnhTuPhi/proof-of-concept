package com.example.espoc.bulk.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.bulk.model.ProductDoc;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one of four ingest strategies and records a {@link BenchmarkResult}.
 *
 * <p>Strategies:
 * <ul>
 *   <li>{@code SINGLE} — one PUT per document; the baseline you should never ship.</li>
 *   <li>{@code BULK_DEFAULT} — _bulk in 1000-doc batches, ES defaults.</li>
 *   <li>{@code BULK_TUNED} — bulk + refresh=-1 + replicas=0 during ingest, restored after.</li>
 *   <li>{@code BULK_PARALLEL} — tuned + N parallel threads.</li>
 * </ul>
 */
@Service
public class BulkBenchmarkService {

    public enum Strategy { SINGLE, BULK_DEFAULT, BULK_TUNED, BULK_PARALLEL }

    public record BenchmarkResult(Strategy strategy, long docs, long elapsedMs,
                                  double docsPerSec, int parallelism, int batchSize) {}

    private static final Logger log = LoggerFactory.getLogger(BulkBenchmarkService.class);

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final ProductGenerator gen;
    private final Map<Strategy, BenchmarkResult> latest = new LinkedHashMap<>();

    @Value("${app.bulk.index-name:bulk_products}")
    private String indexName;

    public BulkBenchmarkService(ElasticsearchClient es, IndexAdmin indexAdmin, ProductGenerator gen) {
        this.es = es;
        this.indexAdmin = indexAdmin;
        this.gen = gen;
    }

    public BenchmarkResult run(Strategy strategy, long count, int parallelism) throws IOException {
        resetIndex();
        if (strategy == Strategy.BULK_TUNED || strategy == Strategy.BULK_PARALLEL) {
            applyIngestSettings();
        }
        long t0 = System.nanoTime();
        try {
            switch (strategy) {
                case SINGLE        -> runSingle(count);
                case BULK_DEFAULT  -> runBulk(count, 1000, 1);
                case BULK_TUNED    -> runBulk(count, 5000, 1);
                case BULK_PARALLEL -> runBulk(count, 5000, parallelism);
            }
        } finally {
            if (strategy == Strategy.BULK_TUNED || strategy == Strategy.BULK_PARALLEL) {
                restoreSearchSettings();
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        double rate = count * 1000.0 / Math.max(1, elapsedMs);
        BenchmarkResult r = new BenchmarkResult(strategy, count, elapsedMs, rate,
                strategy == Strategy.BULK_PARALLEL ? parallelism : 1,
                strategy == Strategy.SINGLE ? 1 : (strategy == Strategy.BULK_DEFAULT ? 1000 : 5000));
        latest.put(strategy, r);
        log.info("BENCH {}: {} docs in {} ms → {} docs/sec",
                strategy, count, elapsedMs, String.format("%.0f", rate));
        return r;
    }

    public Map<Strategy, BenchmarkResult> latest() { return latest; }

    /* -- impl -- */

    private void runSingle(long count) throws IOException {
        for (long i = 0; i < count; i++) {
            ProductDoc d = gen.next(i);
            final ProductDoc doc = d;
            es.index(b -> b.index(indexName).id(doc.id()).document(doc).refresh(Refresh.False));
        }
    }

    private void runBulk(long count, int batchSize, int threads) {
        AtomicLong cursor = new AtomicLong(0);
        AtomicLong indexed = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                while (true) {
                    long start = cursor.getAndAdd(batchSize);
                    if (start >= count) return;
                    int thisBatch = (int) Math.min(batchSize, count - start);
                    List<BulkOperation> ops = new ArrayList<>(thisBatch);
                    for (int i = 0; i < thisBatch; i++) {
                        ProductDoc doc = gen.next(start + i);
                        ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(doc.id()).document(doc))));
                    }
                    try {
                        BulkResponse resp = es.bulk(BulkRequest.of(b -> b.index(indexName).operations(ops)));
                        if (resp.errors()) {
                            long errs = resp.items().stream().filter(it -> it.error() != null).count();
                            log.warn("Bulk had {} item errors at offset {}", errs, start);
                        }
                        long done = indexed.addAndGet(thisBatch);
                        if (done % 50_000 == 0) log.info("  progress: {}", done);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : futures) f.get();
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw ApiException.internal("BULK_FAILED", "Parallel bulk failed", e);
        }
    }

    private void applyIngestSettings() throws IOException {
        es.indices().putSettings(s -> s
                .index(indexName)
                .settings(set -> set.refreshInterval(Time.of(t -> t.time("-1")))
                                    .numberOfReplicas("0")));
        log.info("Applied ingest settings: refresh_interval=-1, replicas=0");
    }

    private void restoreSearchSettings() throws IOException {
        es.indices().putSettings(s -> s
                .index(indexName)
                .settings(set -> set.refreshInterval(Time.of(t -> t.time("1s")))
                                    .numberOfReplicas("0"))); // single-node — keep replicas at 0
        es.indices().refresh(r -> r.index(indexName));
        es.indices().forcemerge(f -> f.index(indexName).maxNumSegments(1L));
        log.info("Restored search settings + refresh + force-merge");
    }

    private void resetIndex() throws IOException {
        indexAdmin.deleteIfExists(indexName);
        indexAdmin.createIfMissing(indexName, "es/products-mapping.json");
    }
}
