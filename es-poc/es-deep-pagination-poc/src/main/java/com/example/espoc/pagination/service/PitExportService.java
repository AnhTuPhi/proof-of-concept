package com.example.espoc.pagination.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.common.web.Cursor;
import com.example.espoc.common.web.CursorPageResponse;
import com.example.espoc.pagination.config.PaginationProperties;
import com.example.espoc.pagination.model.ProductDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * STRATEGY 3 — Point-In-Time (PIT) + {@code search_after}.
 *
 * <p>PIT opens a server-side snapshot of the index. Subsequent {@code search_after} requests against
 * the PIT see a frozen view, even if the live index changes under you. This is what you want for
 * exports, bulk reads, analytics.
 *
 * <p>Cursor here carries both the sort values AND the PIT id (opaque to clients).
 */
@Service
public class PitExportService {

    private static final Logger log = LoggerFactory.getLogger(PitExportService.class);
    private static final Duration KEEP_ALIVE = Duration.ofMinutes(2);

    private final ElasticsearchClient es;
    private final PaginationProperties props;

    public PitExportService(ElasticsearchClient es, PaginationProperties props) {
        this.es = es;
        this.props = props;
    }

    public CursorPageResponse<ProductDoc> nextChunk(String cursor, int size) {
        if (size < 1 || size > 5000) throw ApiException.badRequest("BAD_SIZE", "size must be 1..5000");

        try {
            PitCursor pc = decode(cursor);
            String pitId = pc.pitId;

            if (pitId == null) {
                OpenPointInTimeResponse open = es.openPointInTime(o -> o
                        .index(props.indexName())
                        .keepAlive(t -> t.time(KEEP_ALIVE.toSeconds() + "s")));
                pitId = open.id();
                log.info("Opened PIT {}", pitId.substring(0, Math.min(20, pitId.length())) + "...");
            }

            final String activePit = pitId;
            List<Object> after = pc.sortAfter;
            List<FieldValue> afterValues = after.stream().map(this::toFieldValue).toList();

            SearchRequest req = SearchRequest.of(b -> {
                b.size(size)
                        // NOTE: when using PIT, you do NOT pass index/indices — the PIT carries it.
                        .pit(p -> p.id(activePit).keepAlive(t -> t.time(KEEP_ALIVE.toSeconds() + "s")))
                        .sort(SortOptions.of(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc))))
                        .sort(SortOptions.of(s -> s.field(f -> f.field("id").order(SortOrder.Asc))))
                        .trackTotalHits(t -> t.enabled(false));
                if (!afterValues.isEmpty()) b.searchAfter(afterValues);
                return b;
            });
            SearchResponse<ProductDoc> resp = es.search(req, ProductDoc.class);
            List<Hit<ProductDoc>> hits = resp.hits().hits();
            List<ProductDoc> items = hits.stream().map(Hit::source).toList();

            String nextCursor = null;
            if (hits.size() == size) {
                Hit<ProductDoc> last = hits.get(hits.size() - 1);
                List<Object> sv = new ArrayList<>(last.sort().size());
                for (FieldValue v : last.sort()) sv.add(fromFieldValue(v));
                nextCursor = encode(new PitCursor(activePit, sv));
            } else {
                // No more — close the PIT.
                ClosePointInTimeResponse closed = es.closePointInTime(c -> c.id(activePit));
                log.info("Closed PIT (succeeded={}, freed={})", closed.succeeded(), closed.numFreed());
            }
            return CursorPageResponse.of(items, nextCursor, size, resp.took());
        } catch (IOException e) {
            throw ApiException.internal("PIT_FAILED", "PIT export failed", e);
        }
    }

    /** Cursor encoding for PIT: pitId + sort values as a 2-element JSON list. */
    private record PitCursor(String pitId, List<Object> sortAfter) {}

    private String encode(PitCursor c) {
        return Cursor.encode(List.of(c.pitId, c.sortAfter));
    }
    private PitCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return new PitCursor(null, List.of());
        List<Object> decoded = Cursor.decode(cursor);
        if (decoded.size() != 2) throw ApiException.badRequest("CURSOR_INVALID", "PIT cursor must have 2 parts");
        return new PitCursor((String) decoded.get(0), (List<Object>) decoded.get(1));
    }
    private FieldValue toFieldValue(Object o) {
        if (o == null) return FieldValue.NULL;
        if (o instanceof Number n) return FieldValue.of(n.longValue());
        return FieldValue.of(o.toString());
    }
    private Object fromFieldValue(FieldValue v) {
        return switch (v._kind()) {
            case Long    -> v.longValue();
            case Double  -> v.doubleValue();
            case Boolean -> v.booleanValue();
            case String  -> v.stringValue();
            default      -> v._toJsonString();
        };
    }
}
