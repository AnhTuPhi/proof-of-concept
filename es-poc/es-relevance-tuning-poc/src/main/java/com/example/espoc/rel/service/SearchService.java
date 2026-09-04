package com.example.espoc.rel.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.rel.model.ProductDoc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class SearchService {

    public enum Config { baseline, tuned }

    public record SearchHit(String id, String name, String brand, double score) {}

    private final ElasticsearchClient es;
    @Value("${app.relevance.index-name}") private String indexName;

    public SearchService(ElasticsearchClient es) { this.es = es; }

    public List<SearchHit> search(String query, Config cfg, int size) {
        try {
            SearchResponse<ProductDoc> r = es.search(s -> s
                    .index(indexName)
                    .size(size)
                    .query(buildQuery(query, cfg)), ProductDoc.class);
            return r.hits().hits().stream()
                    .map(h -> new SearchHit(h.id(), h.source().name(), h.source().brand(), h.score() == null ? 0 : h.score()))
                    .toList();
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "search failed", e);
        }
    }

    private Query buildQuery(String q, Config cfg) {
        return switch (cfg) {
            case baseline -> Query.of(b -> b.match(m -> m.field("name").query(q)));
            case tuned    -> Query.of(b -> b.functionScore(fs -> fs
                    .query(qq -> qq.multiMatch(mm -> mm.query(q).fields("name^3", "brand^2", "description")))
                    .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("popularity")
                            .factor(0.5).modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                            .missing(1.0)))
                    .scoreMode(FunctionScoreMode.Sum)
                    .boostMode(FunctionBoostMode.Multiply)));
        };
    }
}
