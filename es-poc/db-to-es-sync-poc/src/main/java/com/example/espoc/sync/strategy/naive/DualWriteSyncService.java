package com.example.espoc.sync.strategy.naive;

import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.sync.es.ProductEsIndexer;
import com.example.espoc.sync.model.NaiveProduct;
import com.example.espoc.sync.model.dto.ProductDto;
import com.example.espoc.sync.repository.NaiveProductRepository;
import com.example.espoc.sync.support.FailureInjector;
import com.example.espoc.sync.support.FailureInjector.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * STRATEGY 1: dual-write — write to DB and ES inside the same method.
 *
 * <p><b>This is intentionally the wrong pattern.</b> The POC keeps it to demonstrate failure modes:
 * <ul>
 *   <li>ES throws → tx rolls back → ES *may* still have the doc if the call partially succeeded.</li>
 *   <li>DB commit fails after ES write → ES has a doc the DB doesn't.</li>
 *   <li>Process killed between DB commit and ES write → DB has it, ES doesn't, forever.</li>
 * </ul>
 *
 * <p>Use {@link com.example.espoc.sync.strategy.outbox.OutboxSyncService} or
 * {@link com.example.espoc.sync.strategy.cdc.CdcSyncService} instead.
 */
@Service
public class DualWriteSyncService {

    private static final Logger log = LoggerFactory.getLogger(DualWriteSyncService.class);

    private final NaiveProductRepository repo;
    private final ProductEsIndexer indexer;
    private final FailureInjector failures;

    public DualWriteSyncService(NaiveProductRepository repo, ProductEsIndexer indexer, FailureInjector failures) {
        this.repo = repo;
        this.indexer = indexer;
        this.failures = failures;
    }

    @Transactional
    public ProductDto save(ProductDto incoming) {
        String id = incoming.id() != null ? incoming.id() : IdGenerators.ulid();
        Instant now = Instant.now();
        NaiveProduct entity = NaiveProduct.builder()
                .id(id)
                .sku(incoming.sku())
                .name(incoming.name())
                .description(incoming.description())
                .priceCents(incoming.priceCents())
                .stock(incoming.stock())
                .createdAt(now)
                .updatedAt(now)
                .build();
        NaiveProduct saved = repo.save(entity);
        ProductDto dto = toDto(saved);

        // The "second write" — and the source of all dual-write pain.
        indexer.index(ProductEsIndexer.IDX_NAIVE, dto);

        // Inject a failure *after* ES write so the rollback leaves drift behind.
        failures.maybeFail(Target.DB);

        log.info("[naive] saved {} (sku={})", saved.getId(), saved.getSku());
        return dto;
    }

    private ProductDto toDto(NaiveProduct e) {
        return new ProductDto(e.getId(), e.getSku(), e.getName(), e.getDescription(),
                e.getPriceCents(), e.getStock(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
