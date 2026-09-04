package com.example.espoc.sizing.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MappingExplosionService {

    private static final Logger log = LoggerFactory.getLogger(MappingExplosionService.class);
    private static final String BAD_INDEX  = "shard_explode_bad";
    private static final String GOOD_INDEX = "shard_explode_good";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;

    public MappingExplosionService(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    /** Forces dynamic mapping to materialize N unique fields under "properties.*". */
    public Map<String, Object> explode(int keys) throws IOException {
        // Recreate with dynamic mapping ON (the default) to actually inflate the mapping.
        indexAdmin.deleteIfExists(BAD_INDEX);
        es.indices().create(c -> c.index(BAD_INDEX));

        Map<String, Object> doc = new HashMap<>();
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keys; i++) props.put("user_" + i, true);
        doc.put("id", "explode-1");
        doc.put("properties", props);

        try {
            es.index(i -> i.index(BAD_INDEX).id("explode-1").document(doc));
        } catch (Exception e) {
            log.error("Explode failed at {} keys — that's the lesson: {}", keys, e.toString());
            return Map.of("index", BAD_INDEX, "keysAttempted", keys, "error", e.getMessage());
        }
        long fieldCount = countFields(BAD_INDEX);
        return Map.of("index", BAD_INDEX, "keysAttempted", keys, "fieldCount", fieldCount);
    }

    /** Same data, but the index uses `flattened` so all of "properties.*" is one field internally. */
    public Map<String, Object> fix(int keys) throws IOException {
        indexAdmin.deleteIfExists(GOOD_INDEX);
        indexAdmin.createIfMissing(GOOD_INDEX, "es/flattened-mapping.json");

        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keys; i++) props.put("user_" + i, true);
        doc.put("id", "explode-1");
        doc.put("properties", props);

        es.index(i -> i.index(GOOD_INDEX).id("explode-1").document(doc));
        long fieldCount = countFields(GOOD_INDEX);
        return Map.of("index", GOOD_INDEX, "keysAttempted", keys, "fieldCount", fieldCount,
                "note", "Properties is one flattened field — mapping stays small regardless of keys.");
    }

    private long countFields(String idx) {
        try {
            return es.fieldCaps(f -> f.index(idx).fields("*")).fields().size();
        } catch (IOException e) {
            throw ApiException.internal("FIELD_CAPS_FAILED", "field_caps failed", e);
        }
    }
}
