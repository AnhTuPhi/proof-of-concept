package com.example.espoc.sync.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.sync.model.dto.ProductDto;
import com.example.espoc.sync.support.FailureInjector;
import com.example.espoc.sync.support.FailureInjector.Target;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes to ES for all three sync strategies, each going to its own index.
 * The dual-write path calls index(...) directly; the outbox & CDC paths call indexFromEvent(...).
 */
@Component
public class ProductEsIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProductEsIndexer.class);

    public static final String IDX_NAIVE  = "products_naive";
    public static final String IDX_OUTBOX = "products_outbox";
    public static final String IDX_CDC    = "products_cdc";

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final FailureInjector failures;

    public ProductEsIndexer(ElasticsearchClient es, IndexAdmin indexAdmin, FailureInjector failures) {
        this.es = es;
        this.indexAdmin = indexAdmin;
        this.failures = failures;
    }

    @PostConstruct
    void ensureIndexes() throws IOException {
        indexAdmin.createIfMissing(IDX_NAIVE,  "es/products-mapping.json");
        indexAdmin.createIfMissing(IDX_OUTBOX, "es/products-mapping.json");
        indexAdmin.createIfMissing(IDX_CDC,    "es/products-mapping.json");
    }

    /**
     * Indexes a product into the given index.
     * Uses external versioning so out-of-order writes (common with retries) don't go backwards.
     */
    public void index(String indexName, ProductDto p) {
        failures.maybeFail(Target.ES);
        long version = p.updatedAt() != null ? p.updatedAt().toEpochMilli() : System.currentTimeMillis();
        try {
            es.index(i -> i
                    .index(indexName)
                    .id(p.id())
                    .document(p)
                    .versionType(VersionType.External)
                    .version(version)
                    .refresh(Refresh.False));
            log.debug("Indexed {} into {} (version={})", p.id(), indexName, version);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ese) {
            // 409 from external versioning means a newer doc is already there. Ignore — that's correct.
            if (ese.status() == 409) {
                log.warn("Skipped older version of {} into {} (current is newer)", p.id(), indexName);
                return;
            }
            throw ApiException.internal("ES_INDEX_FAILED", "ES rejected " + p.id(), ese);
        } catch (IOException ioe) {
            throw ApiException.internal("ES_IO_FAILED", "ES IO error on " + p.id(), ioe);
        }
    }

    public void delete(String indexName, String id) {
        try {
            es.delete(d -> d.index(indexName).id(id));
        } catch (IOException ioe) {
            throw ApiException.internal("ES_DELETE_FAILED", "ES delete failed for " + id, ioe);
        }
    }

    public long count(String indexName) {
        try {
            return es.count(c -> c.index(indexName)).count();
        } catch (IOException ioe) {
            throw ApiException.internal("ES_COUNT_FAILED", "ES count failed for " + indexName, ioe);
        }
    }
}
