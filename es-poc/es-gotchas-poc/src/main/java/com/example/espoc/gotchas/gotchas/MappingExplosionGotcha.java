package com.example.espoc.gotchas.gotchas;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.es.JsonResource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MappingExplosionGotcha implements Gotcha {

    private static final String BAD = "gotcha_mapping_bad";
    private static final String GOOD = "gotcha_mapping_good";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;

    public MappingExplosionGotcha(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @Override public String name() { return "mapping-explosion"; }

    @Override public String explain() {
        return """
                SYMPTOM:  Cluster state grows steadily; recovery takes minutes;
                          eventually "Limit of total fields [1000] in index has been exceeded".

                WHY:      Dynamic mapping (the default) materializes one ES field for every
                          unique JSON key it sees. One buggy producer that emits
                          properties.user-<uuid>=true per request inflates the mapping to
                          tens of thousands of fields.

                FIX:      Use dynamic: strict on known shapes, OR map the unbounded object
                          as type: flattened so the whole sub-object becomes ONE field.
                """;
    }

    @Override
    public Map<String, Object> trigger(Map<String, String> params) throws Exception {
        int keys = Integer.parseInt(params.getOrDefault("keys", "1500"));
        indexAdmin.deleteIfExists(BAD);
        es.indices().create(c -> c.index(BAD));
        Map<String, Object> doc = makeBigDoc(keys);
        try {
            es.index(i -> i.index(BAD).id("1").document(doc));
            return Map.of("index", BAD, "keysAttempted", keys, "outcome", "indexed", "warning", "mapping has " + keys + " fields");
        } catch (Exception e) {
            return Map.of("index", BAD, "keysAttempted", keys, "outcome", "rejected", "error", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> fix(Map<String, String> params) throws Exception {
        int keys = Integer.parseInt(params.getOrDefault("keys", "1500"));
        indexAdmin.deleteIfExists(GOOD);
        String mapping = """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": { "dynamic": "strict",
                                "properties": { "id": { "type": "keyword" },
                                                "properties": { "type": "flattened" } } } }
                """;
        es.indices().create(c -> c.index(GOOD).withJson(JsonResource.fromString(mapping)));
        es.index(i -> i.index(GOOD).id("1").document(makeBigDoc(keys)));
        return Map.of("index", GOOD, "keysAttempted", keys,
                "outcome", "indexed", "note", "Only 2 mapping fields used regardless of key count.");
    }

    private Map<String, Object> makeBigDoc(int keys) {
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keys; i++) props.put("user_" + i, true);
        doc.put("id", "1");
        doc.put("properties", props);
        return doc;
    }
}
