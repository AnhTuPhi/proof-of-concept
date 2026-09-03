package com.example.billing.dunning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "dunning_events")
public class DunningEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(length = 512)
    private String details;

    protected DunningEvent() {
    }

    public DunningEvent(Long subscriptionId, Type type, LocalDate occurredOn, String details) {
        this.subscriptionId = subscriptionId;
        this.type = type;
        this.occurredOn = occurredOn;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public Type getType() {
        return type;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getDetails() {
        return details;
    }

    public enum Type {
        PAYMENT_SUCCEEDED,
        PAYMENT_FAILED,
        RETRY_SCHEDULED,
        EMAIL_SENT,
        SUSPENDED,
        CANCELED,
        REACTIVATED
    }
}
