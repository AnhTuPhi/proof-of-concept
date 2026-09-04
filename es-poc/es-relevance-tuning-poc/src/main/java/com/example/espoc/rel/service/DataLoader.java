package com.example.espoc.rel.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.rel.model.ProductDoc;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds a tiny catalog whose IDs match {@code judgments.json}. Hand-crafted so the relevance tests
 * are deterministic.
 */
@Component
public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    @Value("${app.relevance.index-name}") private String indexName;

    public DataLoader(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @PostConstruct
    public void load() throws IOException {
        indexAdmin.createIfMissing(indexName, "es/products-mapping.json");
        if (es.count(c -> c.index(indexName)).count() > 0) {
            log.info("{} already populated", indexName);
            return;
        }
        List<ProductDoc> docs = new ArrayList<>();
        // iphone-cluster — p1 highest popularity, should beat p2/p3 with tuned config
        docs.add(new ProductDoc("p1", "SKU-1", "iPhone 15 Pro", "Apple", "Apple flagship smartphone", 10_000, 99900));
        docs.add(new ProductDoc("p2", "SKU-2", "iPhone 14",     "Apple", "Last year's iPhone, still great", 5_000, 79900));
        docs.add(new ProductDoc("p3", "SKU-3", "iPhone 13 mini","Apple", "Smaller iPhone, beloved by some", 1_000, 59900));
        docs.add(new ProductDoc("p4", "SKU-4", "iPhone case",   "Generic", "Clear case for iPhone", 200, 999));   // should rank low for "iphone"

        // samsung-cluster
        docs.add(new ProductDoc("p10","SKU-10","Samsung Galaxy S24", "Samsung", "Samsung flagship smartphone", 8_000, 89900));
        docs.add(new ProductDoc("p11","SKU-11","Samsung Galaxy A54", "Samsung", "Mid-range Samsung phone", 4_000, 39900));

        // laptops
        docs.add(new ProductDoc("p20","SKU-20","MacBook Pro 14", "Apple",  "Apple laptop M3 Pro", 7_000, 199900));
        docs.add(new ProductDoc("p21","SKU-21","Dell XPS 15",    "Dell",   "Windows laptop, premium build", 3_000, 149900));
        docs.add(new ProductDoc("p22","SKU-22","ThinkPad X1",    "Lenovo", "Business laptop", 2_500, 159900));

        // running shoes
        docs.add(new ProductDoc("p30","SKU-30","Nike Pegasus 40","Nike","Running shoes, daily trainer", 6_000, 12900));
        docs.add(new ProductDoc("p31","SKU-31","Adidas Ultraboost","Adidas","Running shoes for long distance", 5_500, 18000));

        // coffee
        docs.add(new ProductDoc("p40","SKU-40","Breville Barista Express","Breville","Espresso coffee maker", 3_000, 79900));

        // wireless mouse
        docs.add(new ProductDoc("p50","SKU-50","Logitech MX Master 3","Logitech","Wireless mouse for power users", 5_000, 9999));
        docs.add(new ProductDoc("p51","SKU-51","Apple Magic Mouse","Apple","Wireless mouse for Mac", 2_000, 7999));

        // kindle
        docs.add(new ProductDoc("p60","SKU-60","Kindle Paperwhite","Amazon","E-reader with backlight", 4_000, 14999));

        // headphones
        docs.add(new ProductDoc("p70","SKU-70","Sony WH-1000XM5","Sony","Noise cancelling headphones", 9_000, 39900));
        docs.add(new ProductDoc("p71","SKU-71","Bose QuietComfort","Bose","Premium noise cancelling headphones", 6_000, 32900));

        List<BulkOperation> ops = new ArrayList<>();
        for (ProductDoc d : docs) ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(d.id()).document(d))));
        es.bulk(BulkRequest.of(b -> b.index(indexName).operations(ops)));
        es.indices().refresh(r -> r.index(indexName));
        log.info("Loaded {} judged products into {}", docs.size(), indexName);
    }
}
