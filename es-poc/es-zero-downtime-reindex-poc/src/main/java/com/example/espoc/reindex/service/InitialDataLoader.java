package com.example.espoc.reindex.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.reindex.config.ReindexProperties;
import com.example.espoc.reindex.model.ProductDoc;
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

@Component
public class InitialDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialDataLoader.class);
    private static final String[] WORDS = {"running", "walking", "swimming", "happy", "modern",
            "vintage", "premium", "compact", "wireless", "professional", "lightweight", "durable"};

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final ReindexProperties props;

    public InitialDataLoader(ElasticsearchClient es, IndexAdmin indexAdmin, ReindexProperties props) {
        this.es = es;
        this.indexAdmin = indexAdmin;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        indexAdmin.createIfMissing(props.v1Index(), "es/products-v1-mapping.json");
        indexAdmin.putAlias(props.v1Index(), props.alias());

        long existing = es.count(c -> c.index(props.v1Index())).count();
        if (existing >= props.initialCount()) {
            log.info("{} already has {} docs — skipping load", props.v1Index(), existing);
            return;
        }
        long start = existing;
        long end = props.initialCount();
        int chunk = 2000;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Instant base = Instant.now().minus(180, ChronoUnit.DAYS);

        for (long offset = start; offset < end; offset += chunk) {
            int thisChunk = (int) Math.min(chunk, end - offset);
            List<BulkOperation> ops = new ArrayList<>(thisChunk);
            for (int i = 0; i < thisChunk; i++) {
                long n = offset + i;
                String id = IdGenerators.ulidAt(base.plusSeconds(n));
                String desc = WORDS[r.nextInt(WORDS.length)] + " " + WORDS[r.nextInt(WORDS.length)] + " " + WORDS[r.nextInt(WORDS.length)];
                ProductDoc doc = new ProductDoc(id, "SKU-" + n, "Product " + n, desc, r.nextLong(500, 50000), base.plusSeconds(n));
                ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(id).document(doc))));
            }
            es.bulk(BulkRequest.of(b -> b.index(props.v1Index()).operations(ops)));
        }
        es.indices().refresh(req -> req.index(props.v1Index()));
        log.info("Loaded {} initial products into {}", props.initialCount() - start, props.v1Index());
    }
}
