package com.example.espoc.reindex.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.reindex.config.ReindexProperties;
import com.example.espoc.reindex.model.ProductDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

/**
 * All product writes go through here.
 *
 * <p>Normally writes only to v1 (via the alias). When migration is in {@code DUAL_WRITE_ENABLED}
 * phase, also writes directly to v2 so it stays current during the reindex window.
 *
 * <p>After the swap, the alias points to v2; we keep dual-write on for one more phase so any
 * in-flight requests racing the swap still land in both, then turn it off.
 */
@Service
public class ProductWriter {

    private static final Logger log = LoggerFactory.getLogger(ProductWriter.class);

    private final ElasticsearchClient es;
    private final ReindexProperties props;
    private final MigrationState state;

    public ProductWriter(ElasticsearchClient es, ReindexProperties props, MigrationState state) {
        this.es = es;
        this.props = props;
        this.state = state;
    }

    public ProductDoc save(ProductDoc incoming) {
        String id = incoming.id() != null ? incoming.id() : IdGenerators.ulid();
        ProductDoc doc = new ProductDoc(
                id,
                incoming.sku(),
                incoming.name(),
                incoming.description(),
                incoming.priceCents(),
                incoming.createdAt() != null ? incoming.createdAt() : Instant.now());

        // Always write via alias — that's the production path. After swap, alias → v2.
        try {
            es.index(i -> i.index(props.alias()).id(id).document(doc).refresh(Refresh.False));
        } catch (IOException e) {
            throw ApiException.internal("ES_WRITE_FAILED", "Write to alias failed", e);
        }

        // Dual-write to the "other" index during the migration window.
        if (state.isDualWriteActive()) {
            String otherIndex = state.phase() == MigrationState.Phase.SWAPPED ? props.v1Index() : props.v2Index();
            try {
                es.index(i -> i.index(otherIndex).id(id).document(doc).refresh(Refresh.False));
                log.debug("Dual-write: also wrote {} to {}", id, otherIndex);
            } catch (IOException e) {
                // Don't fail the user-visible request. Surface to ops via metrics / logs.
                log.error("Dual-write to {} failed for {}: {}", otherIndex, id, e.toString());
            }
        }
        return doc;
    }
}
