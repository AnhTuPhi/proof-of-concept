package com.example.espoc.hybrid.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.hybrid.embed.EmbeddingClient;
import com.example.espoc.hybrid.model.ProductDoc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class SearchService {

    public record SearchHit(String id, String name, double score) {}

    private final ElasticsearchClient es;
    private final EmbeddingClient embedder;
    @Value("${app.hybrid.index-name}") private String indexName;

    public SearchService(ElasticsearchClient es, EmbeddingClient embedder) {
        this.es = es; this.embedder = embedder;
    }

    public List<SearchHit> lexical(String q, int k) {
        try {
            SearchResponse<ProductDoc> r = es.search(s -> s.index(indexName).size(k)
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .query(qq -> qq.multiMatch(m -> m.query(q).fields("name^2", "description"))),
                    ProductDoc.class);
            return toHits(r);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "lexical failed", e);
        }
    }

    public List<SearchHit> knn(String q, int k) {
        float[] vec = embedder.embed(q);
        List<Float> vecList = new java.util.ArrayList<>(vec.length);
        for (float f : vec) vecList.add(f);
        try {
            SearchResponse<ProductDoc> r = es.search(s -> s.index(indexName).size(k)
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .knn(kn -> kn.field("embedding").queryVector(vecList).k(k).numCandidates(Math.max(50, k * 5))),
                    ProductDoc.class);
            return toHits(r);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "knn failed", e);
        }
    }

    /**
     * Hybrid using ES 8.8+ RRF retriever. Runs lexical + kNN, fuses by reciprocal rank.
     *
     * <p>Note: this uses the JSON-string form because the typed client's retriever API has been
     * evolving across 8.x; the JSON form is stable.
     */
    public List<SearchHit> hybrid(String q, int k) {
        float[] vec = embedder.embed(q);
        StringBuilder vecJson = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) vecJson.append(",");
            vecJson.append(vec[i]);
        }
        vecJson.append("]");
        String qJson = "\"" + q.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        // The JSON-build-up is intentionally explicit — the typed client's `retriever()` surface
        // changed between 8.10 → 8.15; using rawJson keeps the POC robust to those revs.
        String body = """
                {
                  "size": %d,
                  "_source": { "excludes": ["embedding"] },
                  "retriever": {
                    "rrf": {
                      "retrievers": [
                        { "standard": { "query": { "multi_match": { "query": %s, "fields": ["name^2","description"] } } } },
                        { "knn": { "field": "embedding", "query_vector": %s, "k": %d, "num_candidates": %d } }
                      ],
                      "rank_window_size": %d,
                      "rank_constant": 60
                    }
                  }
                }
                """.formatted(k, qJson, vecJson, k, Math.max(50, k * 5), Math.max(50, k * 5));
        try (var is = com.example.espoc.common.es.JsonResource.fromString(body)) {
            var req = co.elastic.clients.elasticsearch.core.SearchRequest.of(b -> b
                    .index(indexName).withJson(is));
            SearchResponse<ProductDoc> r = es.search(req, ProductDoc.class);
            return toHits(r);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "hybrid failed", e);
        }
    }

    private List<SearchHit> toHits(SearchResponse<ProductDoc> r) {
        return r.hits().hits().stream()
                .map(h -> new SearchHit(h.id(), h.source() == null ? "(no source)" : h.source().name(),
                        h.score() == null ? 0 : h.score()))
                .toList();
    }
}
