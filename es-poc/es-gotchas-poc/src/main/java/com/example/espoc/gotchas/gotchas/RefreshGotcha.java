package com.example.espoc.gotchas.gotchas;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RefreshGotcha implements Gotcha {

    private static final String IDX = "gotcha_refresh";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;

    public RefreshGotcha(ElasticsearchClient es, IndexAdmin indexAdmin) {
        this.es = es; this.indexAdmin = indexAdmin;
    }

    @Override public String name() { return "refresh-too-aggressive"; }

    @Override public String explain() {
        return """
                SYMPTOM:  Bulk-loading 10M docs is taking days instead of hours.

                WHY:      refresh_interval=1s (the default) creates a new Lucene segment
                          every second under heavy write. Each segment is overhead;
                          merges run continuously; throughput collapses.

                FIX:      During bulk ingest, set refresh_interval=-1 and number_of_replicas=0.
                          After ingest: restore both, then _refresh and _forcemerge?max_num_segments=1.
                          See also: es-bulk-indexing-poc for the full benchmark suite.
                """;
    }

    @Override
    public Map<String, Object> trigger(Map<String, String> params) throws Exception {
        int count = Integer.parseInt(params.getOrDefault("count", "20000"));
        recreate();
        // Default settings — refresh_interval=1s, replicas=0 (we're single-node anyway)
        long ms = bulkLoad(count, 1000);
        return Map.of("index", IDX, "count", count, "elapsedMs", ms,
                "docsPerSec", count * 1000.0 / Math.max(1, ms),
                "settings", "refresh_interval=1s (default)");
    }

    @Override
    public Map<String, Object> fix(Map<String, String> params) throws Exception {
        int count = Integer.parseInt(params.getOrDefault("count", "20000"));
        recreate();
        // Disable refresh during ingest
        es.indices().putSettings(s -> s.index(IDX).settings(set -> set.refreshInterval(Time.of(t -> t.time("-1")))));
        long ms = bulkLoad(count, 5000);
        es.indices().putSettings(s -> s.index(IDX).settings(set -> set.refreshInterval(Time.of(t -> t.time("1s")))));
        es.indices().refresh(r -> r.index(IDX));
        es.indices().forcemerge(f -> f.index(IDX).maxNumSegments(1L));
        return Map.of("index", IDX, "count", count, "elapsedMs", ms,
                "docsPerSec", count * 1000.0 / Math.max(1, ms),
                "settings", "refresh_interval=-1 during ingest, restored after + force-merge");
    }

    private void recreate() throws Exception {
        indexAdmin.deleteIfExists(IDX);
        es.indices().create(c -> c.index(IDX));
    }

    private long bulkLoad(int count, int batchSize) throws Exception {
        long t0 = System.nanoTime();
        for (int off = 0; off < count; off += batchSize) {
            int thisBatch = Math.min(batchSize, count - off);
            List<BulkOperation> ops = new ArrayList<>(thisBatch);
            for (int i = 0; i < thisBatch; i++) {
                String id = IdGenerators.ulid();
                ops.add(BulkOperation.of(o -> o.index(it -> it.id(id).document(Map.of("n", id)))));
            }
            es.bulk(BulkRequest.of(b -> b.index(IDX).operations(ops)));
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
