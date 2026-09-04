package com.example.espoc.pagination.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
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
import java.util.ArrayList;
import java.util.List;

/**
 * STRATEGY 2 — {@code search_after}.
 *
 * <p>Stateless cursor: the cursor IS the last hit's sort values. Subsequent pages "skip past" using
 * those values. No coordinator-side discard, so cost is constant per page.
 *
 * <p>Important: every shard must agree on order across all pages. We sort by {@code createdAt DESC, id ASC}
 * — {@code id} is the tiebreaker so two docs with the same {@code createdAt} can't shuffle between pages.
 */
@Service
public class SearchAfterPaginationService {

    private static final Logger log = LoggerFactory.getLogger(SearchAfterPaginationService.class);

    private final ElasticsearchClient es;
    private final PaginationProperties props;

    public SearchAfterPaginationService(ElasticsearchClient es, PaginationProperties props) {
        this.es = es;
        this.props = props;
    }

    public CursorPageResponse<ProductDoc> page(String cursor, int size) {
        if (size < 1 || size > 1000) throw ApiException.badRequest("BAD_SIZE", "size must be 1..1000");

        List<Object> sortAfter = Cursor.decode(cursor);
        List<FieldValue> after = sortAfter.stream().map(this::toFieldValue).toList();

        try {
            SearchRequest req = SearchRequest.of(b -> {
                b.index(props.indexName())
                        .size(size)
                        .sort(SortOptions.of(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc))))
                        .sort(SortOptions.of(s -> s.field(f -> f.field("id").order(SortOrder.Asc))))
                        .trackTotalHits(t -> t.enabled(false));   // skip total count — pointless at depth
                if (!after.isEmpty()) b.searchAfter(after);
                return b;
            });
            SearchResponse<ProductDoc> resp = es.search(req, ProductDoc.class);

            List<Hit<ProductDoc>> hits = resp.hits().hits();
            List<ProductDoc> items = hits.stream().map(Hit::source).toList();

            String nextCursor = null;
            if (!hits.isEmpty() && hits.size() == size) {
                Hit<ProductDoc> last = hits.get(hits.size() - 1);
                List<Object> sv = new ArrayList<>(last.sort().size());
                for (FieldValue v : last.sort()) sv.add(fromFieldValue(v));
                nextCursor = Cursor.encode(sv);
            }
            log.debug("search_after size={} took={}ms gotHits={} hasNext={}",
                    size, resp.took(), items.size(), nextCursor != null);
            return CursorPageResponse.of(items, nextCursor, size, resp.took());
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "ES query failed", e);
        }
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
