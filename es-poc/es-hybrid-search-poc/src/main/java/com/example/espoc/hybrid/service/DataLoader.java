package com.example.espoc.hybrid.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.hybrid.embed.EmbeddingClient;
import com.example.espoc.hybrid.model.ProductDoc;
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

    // Seed dataset: each row carefully crafted to demonstrate where lexical wins, where kNN wins.
    private static final String[][] SEED = {
            // electronics — lots of product codes, lexical-friendly
            {"iPhone 15 Pro",   "Apple flagship smartphone with USB-C and titanium frame", "electronics"},
            {"Samsung Galaxy S24","Premium Android smartphone with AI features",            "electronics"},
            {"RTX 4090 GPU",    "Nvidia top-tier graphics card for gaming and ML",          "electronics"},
            {"MacBook Pro 14",  "Apple laptop with M3 chip, 18GB RAM",                       "electronics"},
            {"AirPods Pro 2",   "Wireless earbuds with active noise cancellation",            "electronics"},
            {"Sony WH-1000XM5", "Over-ear noise cancelling headphones",                       "electronics"},
            {"Anker PowerCore", "Portable wireless charging pad for phones",                   "electronics"},

            // kitchen — semantic-friendly (queries describe purpose, not name)
            {"Breville Barista Express", "Espresso machine, brew coffee at home", "kitchen"},
            {"Nespresso Vertuo",          "Capsule coffee maker, single serve",     "kitchen"},
            {"Vitamix Blender 5200",      "High-power blender for smoothies",       "kitchen"},
            {"Instant Pot Duo",           "Pressure cooker, slow cooker, rice cooker", "kitchen"},
            {"KitchenAid Stand Mixer",    "Mixer for baking bread, cake, dough",    "kitchen"},

            // outdoors
            {"Patagonia Down Jacket",   "Warm winter coat for cold weather",       "outdoors"},
            {"Yeti Tumbler 30oz",       "Insulated water bottle, keeps drinks cold","outdoors"},
            {"Garmin Fenix 7",          "GPS multisport watch with maps",           "outdoors"},

            // books
            {"Designing Data-Intensive Applications", "Software engineering book by Martin Kleppmann", "books"},
            {"The Pragmatic Programmer", "Classic book on becoming a better software engineer",        "books"}
    };

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final EmbeddingClient embedder;
    @Value("${app.hybrid.index-name}") private String indexName;
    @Value("${app.hybrid.sample-size}") private int sampleSize;

    public DataLoader(ElasticsearchClient es, IndexAdmin indexAdmin, EmbeddingClient embedder) {
        this.es = es; this.indexAdmin = indexAdmin; this.embedder = embedder;
    }

    @PostConstruct
    public void load() throws IOException {
        indexAdmin.createIfMissing(indexName, "es/products-mapping.json");
        if (es.count(c -> c.index(indexName)).count() >= SEED.length) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<BulkOperation> ops = new ArrayList<>(SEED.length);
        for (String[] row : SEED) {
            String text = row[0] + " " + row[1];
            ProductDoc d = new ProductDoc(IdGenerators.ulid(), row[0], row[1], row[2],
                    r.nextLong(1000, 200_000), embedder.embed(text));
            ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(d.id()).document(d))));
        }
        es.bulk(BulkRequest.of(b -> b.index(indexName).operations(ops)));
        es.indices().refresh(req -> req.index(indexName));
        log.info("Loaded {} curated products into {}", SEED.length, indexName);
    }
}
