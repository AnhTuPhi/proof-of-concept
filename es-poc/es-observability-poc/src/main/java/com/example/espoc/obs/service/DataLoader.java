package com.example.espoc.obs.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);
    private static final String[] BRANDS = {"Apple", "Samsung", "Sony", "Bose", "Dell"};

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    @Value("${app.observability.index-name}") private String indexName;
    @Value("${app.observability.sample-size}") private int sampleSize;

    public DataLoader(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @PostConstruct
    public void load() throws IOException {
        indexAdmin.createIfMissing(indexName, "es/products-mapping.json");
        if (es.count(c -> c.index(indexName)).count() >= sampleSize) return;

        int chunk = 5000;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int offset = 0; offset < sampleSize; offset += chunk) {
            int thisChunk = Math.min(chunk, sampleSize - offset);
            List<BulkOperation> ops = new ArrayList<>(thisChunk);
            for (int i = 0; i < thisChunk; i++) {
                String id = IdGenerators.ulid();
                Map<String, Object> d = Map.of("id", id,
                        "name", BRANDS[r.nextInt(BRANDS.length)] + " product " + (offset + i),
                        "brand", BRANDS[r.nextInt(BRANDS.length)]);
                ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(id).document(d))));
            }
            es.bulk(BulkRequest.of(b -> b.index(indexName).operations(ops)));
        }
        es.indices().refresh(rq -> rq.index(indexName));
        log.info("Loaded {} docs into {}", sampleSize, indexName);
    }
}
