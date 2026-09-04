package com.example.espoc.common.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.PutAliasResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Small helper around index/alias admin used by several POCs. Idempotent operations only. */
@Component
public class IndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(IndexAdmin.class);

    private final ElasticsearchClient es;

    public IndexAdmin(ElasticsearchClient es) {
        this.es = es;
    }

    /** Create an index from a mapping JSON file on the classpath; no-op if it already exists. */
    public boolean createIfMissing(String indexName, String mappingClasspath) throws IOException {
        if (es.indices().exists(e -> e.index(indexName)).value()) {
            log.info("Index {} already exists — skipping create", indexName);
            return false;
        }
        try (var is = JsonResource.stream(mappingClasspath)) {
            CreateIndexResponse r = es.indices().create(CreateIndexRequest.of(b -> b
                    .index(indexName)
                    .withJson(is)));
            log.info("Created index {} (acknowledged={})", indexName, r.acknowledged());
            return true;
        }
    }

    public void deleteIfExists(String indexName) throws IOException {
        if (es.indices().exists(e -> e.index(indexName)).value()) {
            es.indices().delete(d -> d.index(indexName));
            log.info("Deleted index {}", indexName);
        }
    }

    /** Atomic alias swap: remove alias from oldIndex (if any) and add it to newIndex in one call. */
    public void swapAlias(String alias, String oldIndex, String newIndex) throws IOException {
        var resp = es.indices().updateAliases(u -> u
                .actions(a -> a.remove(r -> r.index(oldIndex).alias(alias)))
                .actions(a -> a.add(add -> add.index(newIndex).alias(alias))));
        log.info("Swapped alias {} {} → {} (acknowledged={})", alias, oldIndex, newIndex, resp.acknowledged());
    }

    /** Add alias to an index (idempotent). */
    public void putAlias(String index, String alias) throws IOException {
        PutAliasResponse r = es.indices().putAlias(p -> p.index(index).name(alias));
        log.info("Aliased {} → {} (acknowledged={})", alias, index, r.acknowledged());
    }
}
