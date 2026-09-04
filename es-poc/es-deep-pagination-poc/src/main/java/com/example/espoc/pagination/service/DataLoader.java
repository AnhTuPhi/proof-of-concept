package com.example.espoc.pagination.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.pagination.config.PaginationProperties;
import com.example.espoc.pagination.model.ProductDoc;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loads N synthetic products on first startup. Idempotent — skips if the index already has data.
 *
 * <p>Generates evenly-spaced {@code createdAt} so {@code sort by createdAt} is meaningful.
 */
@Component
public class DataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private static final String[] BRANDS = {"Acme", "Globex", "Initech", "Umbrella", "Sirius", "Soylent",
            "Hooli", "Stark", "Wayne", "Tyrell"};
    private static final String[] CATEGORIES = {"electronics", "books", "clothing", "kitchen", "outdoors",
            "toys", "office", "grocery"};

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final PaginationProperties props;

    public DataLoader(ElasticsearchClient es, IndexAdmin indexAdmin, PaginationProperties props) {
        this.es = es;
        this.indexAdmin = indexAdmin;
        this.props = props;
    }

    @PostConstruct
    public void ensureIndex() throws IOException {
        indexAdmin.createIfMissing(props.indexName(), "es/products-mapping.json");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.autoLoad()) return;
        long existing = es.count(c -> c.index(props.indexName())).count();
        if (existing >= props.initialLoadCount()) {
            log.info("Index {} already has {} docs (≥ {}). Skipping initial load.",
                    props.indexName(), existing, props.initialLoadCount());
            return;
        }
        long start = existing;
        long target = props.initialLoadCount();
        log.info("Loading {} → {} synthetic products into {}", start, target, props.indexName());
        load(start, target);
    }

    public void load(long startInclusive, long endExclusive) throws IOException {
        long t0 = System.nanoTime();
        long total = endExclusive - startInclusive;
        int chunk = props.bulkChunkSize();
        Instant base = Instant.now().minus(365, ChronoUnit.DAYS);
        long indexed = 0;

        for (long offset = startInclusive; offset < endExclusive; offset += chunk) {
            int thisChunk = (int) Math.min(chunk, endExclusive - offset);
            List<BulkOperation> ops = new ArrayList<>(thisChunk);
            for (int i = 0; i < thisChunk; i++) {
                long n = offset + i;
                ProductDoc doc = synth(n, base);
                ops.add(BulkOperation.of(op -> op
                        .index(idx -> idx.id(doc.id()).document(doc))));
            }
            BulkResponse resp = es.bulk(BulkRequest.of(b -> b.index(props.indexName()).operations(ops)));
            if (resp.errors()) {
                long failed = resp.items().stream().filter(it -> it.error() != null).count();
                log.warn("Bulk had {} item errors", failed);
            }
            indexed += thisChunk;
            if (indexed % 100_000 == 0) {
                double elapsed = (System.nanoTime() - t0) / 1e9;
                log.info("  loaded {} / {} ({} docs/sec)", indexed, total, String.format("%.0f", indexed / elapsed));
            }
        }
        es.indices().refresh(r -> r.index(props.indexName()));
        double elapsed = (System.nanoTime() - t0) / 1e9;
        log.info("Initial load complete: {} docs in {}s ({} docs/sec)",
                total, String.format("%.1f", elapsed), String.format("%.0f", total / elapsed));
    }

    private ProductDoc synth(long n, Instant base) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // Time-sortable id so search_after can use it as tiebreaker without surprises.
        String id = IdGenerators.ulidAt(base.plusSeconds(n));
        String brand = BRANDS[(int) (n % BRANDS.length)];
        String category = CATEGORIES[(int) (n % CATEGORIES.length)];
        return new ProductDoc(
                id,
                "SKU-" + String.format("%08d", n),
                brand + " " + category + " model " + n,
                brand,
                category,
                r.nextLong(500, 200_000),
                r.nextInt(0, 100),
                r.nextFloat() * 5,
                base.plusSeconds(n));
    }
}
