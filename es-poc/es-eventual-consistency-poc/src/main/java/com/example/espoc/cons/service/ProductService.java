package com.example.espoc.cons.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.cons.model.Product;
import com.example.espoc.cons.model.ProductEntity;
import com.example.espoc.cons.repo.ProductRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

@Service
public class ProductService {

    public enum WriteMode { DEFAULT, WAIT_FOR, FORCE_REFRESH }
    public enum ReadMode  { ES_ONLY, READ_THROUGH }

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final ProductRepository repo;
    @Value("${app.consistency.index-name}") private String indexName;

    public ProductService(ElasticsearchClient es, IndexAdmin indexAdmin, ProductRepository repo) {
        this.es = es; this.indexAdmin = indexAdmin; this.repo = repo;
    }

    @PostConstruct
    public void init() throws IOException {
        indexAdmin.createIfMissing(indexName, "es/products-mapping.json");
    }

    @Transactional
    public Product save(Product incoming, WriteMode mode) {
        String id = incoming.id() != null ? incoming.id() : IdGenerators.ulid();
        Instant now = Instant.now();
        ProductEntity e = ProductEntity.builder()
                .id(id).sku(incoming.sku()).name(incoming.name())
                .priceCents(incoming.priceCents()).updatedAt(now)
                .build();
        ProductEntity saved = repo.save(e);
        Product dto = toDto(saved);

        try {
            switch (mode) {
                case DEFAULT -> es.index(i -> i.index(indexName).id(id).document(dto)
                        .versionType(VersionType.External).version(now.toEpochMilli())
                        .refresh(Refresh.False));
                case WAIT_FOR -> es.index(i -> i.index(indexName).id(id).document(dto)
                        .versionType(VersionType.External).version(now.toEpochMilli())
                        .refresh(Refresh.WaitFor));
                case FORCE_REFRESH -> {
                    es.index(i -> i.index(indexName).id(id).document(dto)
                            .versionType(VersionType.External).version(now.toEpochMilli())
                            .refresh(Refresh.False));
                    es.indices().refresh(r -> r.index(indexName));
                }
            }
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ese) {
            if (ese.status() == 409) {
                log.warn("Skipping stale write for {} — newer version exists in ES", id);
            } else {
                throw ApiException.internal("ES_INDEX_FAILED", "indexing failed", ese);
            }
        } catch (IOException ioe) {
            throw ApiException.internal("ES_IO_FAILED", "ES IO error", ioe);
        }
        return dto;
    }

    /** Search the index. */
    public Optional<Product> getById(String id) {
        try {
            var resp = es.get(g -> g.index(indexName).id(id), Product.class);
            return resp.found() ? Optional.ofNullable(resp.source()) : Optional.empty();
        } catch (IOException e) {
            throw ApiException.internal("ES_GET_FAILED", "GET failed", e);
        }
    }

    /** Read-through: if ES misses, fall back to DB AND back-fill ES. */
    public Optional<Product> getByIdReadThrough(String id) {
        Optional<Product> fromEs = getById(id);
        if (fromEs.isPresent()) return fromEs;
        return repo.findById(id).map(e -> {
            Product dto = toDto(e);
            try {
                es.index(i -> i.index(indexName).id(id).document(dto)
                        .versionType(VersionType.External).version(e.getUpdatedAt().toEpochMilli())
                        .refresh(Refresh.True));   // back-fill is hot path → force refresh for caller
                log.info("Read-through back-filled {} from DB into ES", id);
            } catch (Exception ex) {
                // Don't fail the read just because we couldn't back-fill.
                log.warn("Back-fill of {} failed: {}", id, ex.toString());
            }
            return dto;
        });
    }

    /** Force a stale write — used by version-skew demo. */
    public void forceWriteWithVersion(Product p, long versionMs) {
        try {
            es.index(i -> i.index(indexName).id(p.id()).document(p)
                    .versionType(VersionType.External).version(versionMs).refresh(Refresh.True));
            log.info("Wrote {} with version={}", p.id(), versionMs);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ese) {
            if (ese.status() == 409) {
                log.warn("ES rejected stale write for {} (version={})", p.id(), versionMs);
            } else {
                throw ApiException.internal("ES_INDEX_FAILED", "version write failed", ese);
            }
        } catch (IOException ioe) {
            throw ApiException.internal("ES_IO_FAILED", "ES IO error", ioe);
        }
    }

    private Product toDto(ProductEntity e) {
        return new Product(e.getId(), e.getSku(), e.getName(), e.getPriceCents(), e.getUpdatedAt());
    }
}
