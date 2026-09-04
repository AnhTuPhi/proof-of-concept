package com.example.espoc.obs.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.espoc.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ElasticsearchClient es;
    @Value("${app.observability.index-name}") private String indexName;

    public ProductController(ElasticsearchClient es) { this.es = es; }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q) {
        try {
            var r = es.search(s -> s.index(indexName).size(5)
                    .query(qq -> qq.match(m -> m.field("name").query(q))), Map.class);
            return Map.of("totalHits", r.hits().total() == null ? 0 : r.hits().total().value(),
                    "tookMs", r.took());
        } catch (IOException e) {
            throw ApiException.internal("SEARCH_FAILED", "search failed", e);
        }
    }

    /** Intentionally bad — leading wildcard. Used to induce slowness for diagnostics demos. */
    @GetMapping("/wildcard")
    public Map<String, Object> wildcard(@RequestParam String q) {
        try {
            var r = es.search(s -> s.index(indexName).size(5)
                    .query(qq -> qq.wildcard(w -> w.field("name").value("*" + q + "*"))), Map.class);
            return Map.of("totalHits", r.hits().total() == null ? 0 : r.hits().total().value(),
                    "tookMs", r.took());
        } catch (IOException e) {
            throw ApiException.internal("WILDCARD_FAILED", "wildcard query failed", e);
        }
    }
}
