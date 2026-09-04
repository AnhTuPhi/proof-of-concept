package com.example.espoc.gotchas.gotchas;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.es.JsonResource;
import com.example.espoc.common.id.IdGenerators;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class WildcardPrefixGotcha implements Gotcha {

    private static final String BAD  = "gotcha_wildcard_bad";
    private static final String GOOD = "gotcha_wildcard_good";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;

    public WildcardPrefixGotcha(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @Override public String name() { return "wildcard-prefix"; }

    @Override public String explain() {
        return """
                SYMPTOM:  One slow query stalls a node. CPU pinned on a single thread.

                WHY:      "*term" or "*term*" forces ES to scan the entire term dictionary
                          for every shard. On a 100M-doc index, a single such query can
                          stall the cluster.

                FIX:      Index n-grams at write time so the query becomes plain match.
                          OR reject leading wildcards at the API layer with a clear error.
                          See also: es-autocomplete-poc.
                """;
    }

    @Override
    public Map<String, Object> trigger(Map<String, String> params) throws Exception {
        String term = params.getOrDefault("term", "phone");
        ensure(BAD, """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": { "properties": { "name": { "type": "text" } } } }
                """);
        seedIfEmpty(BAD, 50_000);
        long t0 = System.nanoTime();
        var r = es.search(s -> s.index(BAD).size(5)
                .query(q -> q.wildcard(w -> w.field("name").value("*" + term + "*"))), Map.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return Map.of("index", BAD, "term", term, "totalHits", r.hits().total() == null ? 0 : r.hits().total().value(),
                "esTookMs", r.took(), "clientTotalMs", ms,
                "warning", "Leading-wildcard query — feel free to compare against /fix");
    }

    @Override
    public Map<String, Object> fix(Map<String, String> params) throws Exception {
        String term = params.getOrDefault("term", "phone");
        ensure(GOOD, """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0,
                                "analysis": { "analyzer": {
                                  "ng": { "type": "custom", "tokenizer": "ng_tok", "filter": ["lowercase"] } },
                                  "tokenizer": { "ng_tok": { "type": "ngram", "min_gram": 3, "max_gram": 5 } } } },
                  "mappings": { "properties": {
                                  "name_ngram": { "type": "text", "analyzer": "ng", "search_analyzer": "standard" } } } }
                """);
        seedIfEmpty(GOOD, 50_000);
        long t0 = System.nanoTime();
        var r = es.search(s -> s.index(GOOD).size(5)
                .query(q -> q.match(m -> m.field("name_ngram").query(term))), Map.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return Map.of("index", GOOD, "term", term, "totalHits", r.hits().total() == null ? 0 : r.hits().total().value(),
                "esTookMs", r.took(), "clientTotalMs", ms,
                "note", "Plain match against n-gram-indexed field. Constant cost regardless of corpus size.");
    }

    private void ensure(String idx, String mapping) throws Exception {
        if (!es.indices().exists(e -> e.index(idx)).value()) {
            es.indices().create(c -> c.index(idx).withJson(JsonResource.fromString(mapping)));
        }
    }

    private void seedIfEmpty(String idx, int n) throws Exception {
        if (es.count(c -> c.index(idx)).count() >= n) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String[] words = {"phone", "tablet", "watch", "laptop", "headphones", "speaker", "camera", "monitor"};
        int batch = 5000;
        for (int off = 0; off < n; off += batch) {
            int thisBatch = Math.min(batch, n - off);
            List<BulkOperation> ops = new ArrayList<>(thisBatch);
            for (int i = 0; i < thisBatch; i++) {
                String id = IdGenerators.ulid();
                String text = words[r.nextInt(words.length)] + " model " + (off + i);
                ops.add(BulkOperation.of(o -> o.index(it -> it.id(id)
                        .document(idx.equals(GOOD)
                                ? Map.of("name_ngram", text)
                                : Map.of("name", text)))));
            }
            es.bulk(BulkRequest.of(b -> b.index(idx).operations(ops)));
        }
        es.indices().refresh(rq -> rq.index(idx));
    }
}
