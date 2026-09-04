package com.example.espoc.gotchas.gotchas;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
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
public class FielddataGotcha implements Gotcha {

    private static final String BAD  = "gotcha_fielddata_bad";
    private static final String GOOD = "gotcha_fielddata_good";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;

    public FielddataGotcha(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @Override public String name() { return "fielddata-oom"; }

    @Override public String explain() {
        return """
                SYMPTOM:  Heap usage climbs and doesn't return. Eventually OOM.

                WHY:      ES sorts/aggregates on a `text` field by loading fielddata —
                          a per-document inverted-of-inverted index in JVM heap.
                          Default `fielddata: false` on text fields *rejects* the request,
                          but if you set fielddata: true (don't!) or use older indexes,
                          memory leaks forever.

                FIX:      Use a `keyword` sub-field. Sort on `name.keyword`, not `name`.
                          The default multi-field mapping {"type": "text", "fields": {"keyword": {"type": "keyword"}}}
                          gives you both.
                """;
    }

    @Override
    public Map<String, Object> trigger(Map<String, String> params) throws Exception {
        recreate(BAD, """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": { "properties": { "name": { "type": "text", "fielddata": true } } } }
                """);
        seed(BAD, 5000);
        try {
            var r = es.search(s -> s.index(BAD).size(5)
                    .sort(SortOptions.of(so -> so.field(f -> f.field("name").order(SortOrder.Asc)))), Map.class);
            return Map.of("index", BAD, "outcome", "sorted",
                    "warning", "Fielddata was loaded into the heap. Check _nodes/stats indices.fielddata.memory_size.",
                    "tookMs", r.took());
        } catch (Exception e) {
            return Map.of("index", BAD, "outcome", "rejected", "error", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> fix(Map<String, String> params) throws Exception {
        recreate(GOOD, """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": { "properties": { "name": { "type": "text",
                                                "fields": { "keyword": { "type": "keyword" } } } } } }
                """);
        seed(GOOD, 5000);
        var r = es.search(s -> s.index(GOOD).size(5)
                .sort(SortOptions.of(so -> so.field(f -> f.field("name.keyword").order(SortOrder.Asc)))), Map.class);
        return Map.of("index", GOOD, "outcome", "sorted",
                "note", "Sorted on name.keyword — no fielddata involved.", "tookMs", r.took());
    }

    private void recreate(String idx, String mapping) throws Exception {
        indexAdmin.deleteIfExists(idx);
        es.indices().create(c -> c.index(idx).withJson(JsonResource.fromString(mapping)));
    }

    private void seed(String idx, int n) throws Exception {
        List<BulkOperation> ops = new ArrayList<>(n);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            String id = IdGenerators.ulid();
            String name = "name-" + r.nextInt(1_000_000);
            ops.add(BulkOperation.of(o -> o.index(it -> it.id(id).document(Map.of("name", name)))));
        }
        es.bulk(BulkRequest.of(b -> b.index(idx).operations(ops)));
        es.indices().refresh(rq -> rq.index(idx));
    }
}
