package com.example.cdc.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Deduplication ledger. Kafka delivers at-least-once, so the consumer can see
 * the same event twice (e.g. after a rebalance). We insert the event_id here
 * inside the same transaction as the side-effect (or before publishing it),
 * and a PK violation tells us "already handled — skip".
 *
 * Implements {@link Persistable} so {@code save()} skips the wasted
 * SELECT-before-INSERT that Spring Data JPA would otherwise issue for an
 * entity with a pre-assigned ID.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String eventType, String aggregateId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.processedAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
