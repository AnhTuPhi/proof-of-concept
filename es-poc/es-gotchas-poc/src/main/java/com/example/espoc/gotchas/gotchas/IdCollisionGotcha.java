package com.example.espoc.gotchas.gotchas;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.VersionType;
import com.example.espoc.common.es.IndexAdmin;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IdCollisionGotcha implements Gotcha {

    private static final String IDX = "gotcha_id_collision";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;

    public IdCollisionGotcha(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @Override public String name() { return "id-collision"; }

    @Override public String explain() {
        return """
                SYMPTOM:  Indexed docs have older content than expected, no errors logged.

                WHY:      Indexing two writes with the same _id silently overwrites.
                          A common cause: indexer process crashed, resumed from older offset,
                          re-applied older events on top of newer ones.

                FIX:      EITHER op_type: "create" → 409 on duplicate (good if you want
                          truly idempotent inserts).
                          OR version_type: "external" + monotonic version (e.g. updated_at ms)
                          → ES rejects writes with stale versions automatically.
                """;
    }

    @Override
    public Map<String, Object> trigger(Map<String, String> params) throws Exception {
        recreate();
        // Two writes, same id, conflicting content; both succeed → last write wins
        es.index(i -> i.index(IDX).id("X").document(Map.of("name", "new name", "version", 2)));
        es.index(i -> i.index(IDX).id("X").document(Map.of("name", "old name", "version", 1)));   // overwrites
        es.indices().refresh(r -> r.index(IDX));
        var got = es.get(g -> g.index(IDX).id("X"), Map.class);
        return Map.of("index", IDX, "id", "X", "current", got.source(),
                "warning", "Last write won — but the older event arrived last. Silent corruption.");
    }

    @Override
    public Map<String, Object> fix(Map<String, String> params) throws Exception {
        recreate();
        // External versioning: ES rejects writes with stale versions
        es.index(i -> i.index(IDX).id("X").document(Map.of("name", "new name", "version", 2))
                .versionType(VersionType.External).version(2));
        try {
            es.index(i -> i.index(IDX).id("X").document(Map.of("name", "old name", "version", 1))
                    .versionType(VersionType.External).version(1));   // should be rejected with 409
            return Map.of("status", "did NOT reject — surprising");
        } catch (Exception e) {
            es.indices().refresh(r -> r.index(IDX));
            var got = es.get(g -> g.index(IDX).id("X"), Map.class);
            return Map.of("index", IDX, "id", "X", "current", got.source(),
                    "note", "Stale write rejected by external versioning. Good.",
                    "rejectedError", e.getMessage());
        }
    }

    private void recreate() throws Exception {
        indexAdmin.deleteIfExists(IDX);
        es.indices().create(c -> c.index(IDX));
    }
}
