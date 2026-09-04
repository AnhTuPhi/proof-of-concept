package com.example.espoc.sync.strategy.cdc;

import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.sync.model.CdcProduct;
import com.example.espoc.sync.model.dto.ProductDto;
import com.example.espoc.sync.repository.CdcProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * STRATEGY 3: CDC via Debezium embedded engine.
 *
 * <p>App code is unaware of ES. It just writes to Postgres. The {@link DebeziumEngineRunner}
 * tails the WAL and pushes changes to ES.
 *
 * <p>Properties:
 * <ul>
 *   <li>No app-side outbox to manage.</li>
 *   <li>Captures DELETEs naturally (Debezium emits delete events from the WAL).</li>
 *   <li>Lag is typically lower than the outbox path.</li>
 * </ul>
 *
 * <p>Operational risk: replication slot can fill the DB disk if the engine stops draining.
 * Monitor {@code pg_replication_slots} → {@code confirmed_flush_lsn} vs current WAL.
 */
@Service
public class CdcSyncService {

    private static final Logger log = LoggerFactory.getLogger(CdcSyncService.class);

    private final CdcProductRepository repo;

    public CdcSyncService(CdcProductRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public ProductDto save(ProductDto incoming) {
        String id = incoming.id() != null ? incoming.id() : IdGenerators.ulid();
        Instant now = Instant.now();
        CdcProduct entity = CdcProduct.builder()
                .id(id)
                .sku(incoming.sku())
                .name(incoming.name())
                .description(incoming.description())
                .priceCents(incoming.priceCents())
                .stock(incoming.stock())
                .createdAt(now)
                .updatedAt(now)
                .build();
        CdcProduct saved = repo.save(entity);
        log.info("[cdc] saved product {} — CDC engine will pick it up from WAL", saved.getId());
        return toDto(saved);
    }

    @Transactional
    public void delete(String id) {
        repo.deleteById(id);
    }

    private ProductDto toDto(CdcProduct e) {
        return new ProductDto(e.getId(), e.getSku(), e.getName(), e.getDescription(),
                e.getPriceCents(), e.getStock(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
