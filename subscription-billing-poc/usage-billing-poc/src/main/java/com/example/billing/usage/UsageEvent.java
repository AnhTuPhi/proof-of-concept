package com.example.billing.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Entity
@Table(name = "usage_events",
        uniqueConstraints = @UniqueConstraint(name = "ux_usage_event_id", columnNames = "event_id"),
        indexes = {
                @Index(name = "ix_usage_customer_day", columnList = "customer_id,usage_day"),
                @Index(name = "ix_usage_metric_day", columnList = "metric,usage_day")
        })
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Client-supplied idempotency key. Replays with the same event_id are dropped. */
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UsageMetric metric;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal quantity;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** UTC date derived from occurredAt; the unit of rollup. */
    @Column(name = "usage_day", nullable = false)
    private LocalDate usageDay;

    protected UsageEvent() {
    }

    public UsageEvent(String eventId, String customerId, UsageMetric metric,
                      BigDecimal quantity, Instant occurredAt) {
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must be non-negative");
        }
        this.eventId = eventId;
        this.customerId = customerId;
        this.metric = metric;
        this.quantity = quantity;
        this.occurredAt = occurredAt;
        this.usageDay = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public UsageMetric getMetric() {
        return metric;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public LocalDate getUsageDay() {
        return usageDay;
    }
}
