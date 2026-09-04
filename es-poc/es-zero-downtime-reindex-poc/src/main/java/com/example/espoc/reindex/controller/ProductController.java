package com.example.espoc.reindex.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.reindex.config.ReindexProperties;
import com.example.espoc.reindex.model.ProductDoc;
import com.example.espoc.reindex.service.ProductWriter;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductWriter writer;
    private final ElasticsearchClient es;
    private final ReindexProperties props;

    public ProductController(ProductWriter writer, ElasticsearchClient es, ReindexProperties props) {
        this.writer = writer;
        this.es = es;
        this.props = props;
    }

    @PostMapping
    public ProductDoc save(@RequestBody ProductDoc in) { return writer.save(in); }

    @GetMapping("/search")
    public List<ProductDoc> search(@RequestParam String q) throws IOException {
        var resp = es.search(s -> s
                .index(props.alias())
                .size(10)
                .query(qq -> qq.multiMatch(m -> m.query(q).fields("name", "description"))),
                ProductDoc.class);
        return resp.hits().hits().stream().map(h -> h.source()).toList();
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("alias", safeCount(props.alias()));
        out.put("v1", safeCount(props.v1Index()));
        out.put("v2", safeCount(props.v2Index()));
        return out;
    }

    private long safeCount(String name) {
        try {
            if (!es.indices().exists(e -> e.index(name)).value()) return -1;
            return es.count(c -> c.index(name)).count();
        } catch (IOException e) {
            throw ApiException.internal("COUNT_FAILED", "count " + name, e);
        }
    }
}
