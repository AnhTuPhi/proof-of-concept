package com.example.espoc.sync.strategy.outbox;

import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.sync.model.OutboxEvent;
import com.example.espoc.sync.model.OutboxProduct;
import com.example.espoc.sync.model.dto.ProductDto;
import com.example.espoc.sync.repository.OutboxEventRepository;
import com.example.espoc.sync.repository.OutboxProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * STRATEGY 2: transactional outbox.
 *
 * <p>Writes the product *and* an outbox event in the same Postgres transaction. They commit or
 * roll back together — there is no "DB has it, ES doesn't" failure mode by construction.
 *
 * <p>Asynchronously, {@link OutboxPoller} reads the outbox table and publishes events to Kafka.
 * {@link OutboxConsumer} reads from Kafka and writes to ES with external versioning (idempotent).
 *
 * <p>This is the recommended pattern for most teams.
 */
@Service
public class OutboxSyncService {

    private static final Logger log = LoggerFactory.getLogger(OutboxSyncService.class);
    private static final String AGGREGATE_TYPE = "Product";
    private static final String EVENT_TYPE_UPSERT = "PRODUCT_UPSERTED";
    private static final String EVENT_TYPE_DELETE = "PRODUCT_DELETED";

    private final OutboxProductRepository productRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper mapper;

    public OutboxSyncService(OutboxProductRepository productRepo, OutboxEventRepository outboxRepo, ObjectMapper mapper) {
        this.productRepo = productRepo;
        this.outboxRepo = outboxRepo;
        this.mapper = mapper;
    }

    @Transactional
    public ProductDto save(ProductDto incoming) {
        String id = incoming.id() != null ? incoming.id() : IdGenerators.ulid();
        Instant now = Instant.now();
        OutboxProduct entity = OutboxProduct.builder()
                .id(id)
                .sku(incoming.sku())
                .name(incoming.name())
                .description(incoming.description())
                .priceCents(incoming.priceCents())
                .stock(incoming.stock())
                .createdAt(now)
                .updatedAt(now)
                .build();
        OutboxProduct saved = productRepo.save(entity);

        ProductDto dto = toDto(saved);
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(saved.getId())
                .eventType(EVENT_TYPE_UPSERT)
                .payload(serialize(dto))
                .build();
        outboxRepo.save(event);

        log.info("[outbox] saved product {} + outbox event {} (committed together)", saved.getId(), event.getId());
        return dto;
    }

    @Transactional
    public void delete(String id) {
        productRepo.deleteById(id);
        outboxRepo.save(OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(id)
                .eventType(EVENT_TYPE_DELETE)
                .payload("{\"id\":\"" + id + "\"}")
                .build());
    }

    private String serialize(ProductDto dto) {
        try { return mapper.writeValueAsString(dto); }
        catch (JsonProcessingException e) {
            throw ApiException.internal("OUTBOX_SERIALIZE_FAILED", "Could not serialize product", e);
        }
    }

    private ProductDto toDto(OutboxProduct e) {
        return new ProductDto(e.getId(), e.getSku(), e.getName(), e.getDescription(),
                e.getPriceCents(), e.getStock(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
