package com.example.espoc.facets.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.facets.model.ProductDoc;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private static final String[] BRANDS = {"Apple", "Samsung", "Sony", "Bose", "Dell", "Lenovo", "Microsoft", "Google"};
    private static final String[] CATEGORIES = {"electronics", "appliances", "clothing", "books", "kitchen", "outdoors"};

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    @Value("${app.facets.index-name}") private String indexName;
    @Value("${app.facets.sample-size}") private int sampleSize;

    public DataLoader(ElasticsearchClient es, IndexAdmin indexAdmin) { this.es = es; this.indexAdmin = indexAdmin; }

    @PostConstruct
    public void load() throws IOException {
        indexAdmin.createIfMissing(indexName, "es/products-mapping.json");
        if (es.count(c -> c.index(indexName)).count() >= sampleSize) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<BulkOperation> ops = new ArrayList<>(sampleSize);
        for (int i = 0; i < sampleSize; i++) {
            String brand = BRANDS[r.nextInt(BRANDS.length)];
            String cat = CATEGORIES[r.nextInt(CATEGORIES.length)];
            ProductDoc d = new ProductDoc(IdGenerators.ulid(), brand + " " + cat + " " + i,
                    brand, cat, r.nextLong(500, 50_000), r.nextInt(1, 6));
            ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(d.id()).document(d))));
        }
        es.bulk(BulkRequest.of(b -> b.index(indexName).operations(ops)));
        es.indices().refresh(req -> req.index(indexName));
        log.info("Loaded {} docs into {}", sampleSize, indexName);
    }
}
