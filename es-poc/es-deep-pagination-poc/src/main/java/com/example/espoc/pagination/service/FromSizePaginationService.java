package com.example.espoc.pagination.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.common.web.PageResponse;
import com.example.espoc.pagination.config.PaginationProperties;
import com.example.espoc.pagination.model.ProductDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * STRATEGY 1 — {@code from + size}. The naive way.
 *
 * <p>Each request collects {@code from + size} hits per shard at the coordinator, sorts them, then
 * discards {@code from}. Cost grows linearly with {@code from} and is hard-capped by
 * {@code index.max_result_window} (default 10,000).
 */
@Service
public class FromSizePaginationService {

    private static final Logger log = LoggerFactory.getLogger(FromSizePaginationService.class);

    private final ElasticsearchClient es;
    private final PaginationProperties props;

    public FromSizePaginationService(ElasticsearchClient es, PaginationProperties props) {
        this.es = es;
        this.props = props;
    }

    public PageResponse<ProductDoc> page(int page, int size) {
        if (page < 1) throw ApiException.badRequest("BAD_PAGE", "page must be ≥ 1");
        if (size < 1 || size > 100) throw ApiException.badRequest("BAD_SIZE", "size must be 1..100");

        int from = (page - 1) * size;
        if (from + size > 10_000) {
            // Helpful error — mimics ES's own 400 but with a hint about the alternative.
            throw ApiException.badRequest("DEEP_PAGINATION",
                    "from + size = " + (from + size) + " exceeds max_result_window (10000). " +
                    "Use /api/v1/products/scroll (search_after) for deep pagination.");
        }

        try {
            SearchRequest req = SearchRequest.of(b -> b
                    .index(props.indexName())
                    .from(from)
                    .size(size)
                    .sort(SortOptions.of(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc))))
                    .sort(SortOptions.of(s -> s.field(f -> f.field("id").order(SortOrder.Asc))))
                    .trackTotalHits(t -> t.enabled(true)));
            SearchResponse<ProductDoc> resp = es.search(req, ProductDoc.class);

            List<ProductDoc> items = resp.hits().hits().stream().map(Hit::source).toList();
            long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
            long took = resp.took();
            log.debug("from+size page={} size={} took={}ms (from={})", page, size, took, from);
            return PageResponse.of(items, total, page, size, took);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "ES query failed", e);
        }
    }
}
