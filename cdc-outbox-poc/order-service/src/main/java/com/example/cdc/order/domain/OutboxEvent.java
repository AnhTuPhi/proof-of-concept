package com.example.cdc.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox row consumed by Debezium's Outbox Event Router SMT.
 *
 * Column layout matches the SMT defaults so configuration stays minimal:
 *   id              → message key (UUID)
 *   aggregate_type  → routes to topic "outbox.event.<aggregate_type>"
 *   aggregate_id    → kafka partition key
 *   event_type      → header
 *   payload         → message body (JSONB)
 *   created_at      → for retention queries
 *
 * Rows are inserted in the same TX as the business write, then deleted after
 * Debezium has captured them (or by the scheduled cleanup job).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(UUID id,
                        String aggregateType,
                        String aggregateId,
                        String eventType,
                        String payload,
                        Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static OutboxEvent of(String aggregateType,
                                 String aggregateId,
                                 String eventType,
                                 String payload) {
        return new OutboxEvent(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                payload,
                Instant.now()
        );
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
