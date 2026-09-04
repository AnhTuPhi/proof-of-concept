package com.example.espoc.ac.service;

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

    private static final String[] BRANDS = {"Apple", "Samsung", "Sony", "Bose", "Lenovo", "Dell", "Microsoft",
            "Google", "Amazon", "Nike", "Adidas", "Logitech", "Razer", "Acer"};
    private static final String[] PRODUCTS = {"iPhone", "Galaxy", "MacBook", "ThinkPad", "Pixel", "Surface", "Kindle",
            "Echo", "AirPods", "MX Master", "Pegasus", "Ultraboost", "Studio", "Aspire"};

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    @Value("${app.autocomplete.ngram-index}")      private String ngramIndex;
    @Value("${app.autocomplete.completion-index}") private String completionIndex;
    @Value("${app.autocomplete.sayt-index}")       private String saytIndex;
    @Value("${app.autocomplete.sample-size}")      private int sampleSize;

    public DataLoader(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @PostConstruct
    public void load() throws IOException {
        ensure(ngramIndex,      "es/ngram-mapping.json");
        ensure(completionIndex, "es/completion-mapping.json");
        ensure(saytIndex,       "es/sayt-mapping.json");

        if (es.count(c -> c.index(ngramIndex)).count() >= sampleSize) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<BulkOperation> ngramOps = new ArrayList<>();
        List<BulkOperation> completionOps = new ArrayList<>();
        List<BulkOperation> saytOps = new ArrayList<>();

        for (int i = 0; i < sampleSize; i++) {
            String id = IdGenerators.ulid();
            String name = BRANDS[r.nextInt(BRANDS.length)] + " " + PRODUCTS[r.nextInt(PRODUCTS.length)] + " " + (i % 100);
            ngramOps.add(BulkOperation.of(o -> o.index(idx -> idx.id(id).document(Map.of("id", id, "name", name)))));
            completionOps.add(BulkOperation.of(o -> o.index(idx -> idx.id(id).document(
                    Map.of("id", id, "name", name, "suggest", Map.of("input", List.of(name)))))));
            saytOps.add(BulkOperation.of(o -> o.index(idx -> idx.id(id).document(Map.of("id", id, "name", name)))));
        }
        es.bulk(BulkRequest.of(b -> b.index(ngramIndex).operations(ngramOps)));
        es.bulk(BulkRequest.of(b -> b.index(completionIndex).operations(completionOps)));
        es.bulk(BulkRequest.of(b -> b.index(saytIndex).operations(saytOps)));
        es.indices().refresh(r2 -> r2.index(ngramIndex));
        es.indices().refresh(r2 -> r2.index(completionIndex));
        es.indices().refresh(r2 -> r2.index(saytIndex));
        log.info("Loaded {} docs into each of three autocomplete indexes", sampleSize);
    }

    private void ensure(String idx, String mapping) throws IOException {
        indexAdmin.createIfMissing(idx, mapping);
    }
}
