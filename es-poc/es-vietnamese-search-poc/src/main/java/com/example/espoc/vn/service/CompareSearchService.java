package com.example.espoc.vn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.vn.config.VnProperties;
import com.example.espoc.vn.model.VnProduct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CompareSearchService {

    private final ElasticsearchClient es;
    private final VnProperties props;

    public CompareSearchService(ElasticsearchClient es, VnProperties props) {
        this.es = es;
        this.props = props;
    }

    public Map<String, Object> compare(String query) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("standard", probe(props.standardIndex(), query));
        out.put("folded",   probe(props.foldedIndex(),   query));
        if (props.icuEnabled()) out.put("icu", probe(props.icuIndex(), query));
        return out;
    }

    private Map<String, Object> probe(String index, String query) {
        try {
            SearchResponse<VnProduct> r = es.search(s -> s
                    .index(index)
                    .size(5)
                    .query(q -> q.multiMatch(m -> m.query(query).fields("name", "description")))
                    .trackTotalHits(t -> t.enabled(true)), VnProduct.class);
            List<String> samples = r.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .map(VnProduct::name)
                    .toList();
            long total = r.hits().total() == null ? 0 : r.hits().total().value();
            return Map.of("totalHits", total, "tookMs", r.took(), "topNames", samples);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "search " + index + " failed", e);
        }
    }
}
