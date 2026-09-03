package com.demo.patterns.cqrses;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Append-only event store row. The unique constraint on
 * (aggregateId, version) is what enforces optimistic concurrency: two
 * writers loading the same state will both attempt version = N+1, and
 * the second commit will hit the constraint and have to retry.
 */
@Entity
@Table(name = "account_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"aggregateId", "version"}),
        indexes = @Index(name = "idx_event_aggregate", columnList = "aggregateId, version"))
public class AccountEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, length = 1000)
    private String payload;

    @Column(nullable = false)
    private Instant occurredAt;

    protected AccountEvent() {}

    public AccountEvent(String aggregateId, long version, String type, String payload) {
        this.aggregateId = aggregateId;
        this.version = version;
        this.type = type;
        this.payload = payload;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public long getVersion() { return version; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
}
