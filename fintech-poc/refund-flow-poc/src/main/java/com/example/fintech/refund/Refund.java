package com.example.fintech.refund;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds",
        uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"))
public class Refund {

    @Id
    private String id;

    @Column(nullable = false)
    private String paymentId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private RefundChannel channel;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant settledAt;

    protected Refund() {}

    public Refund(String paymentId, String idempotencyKey, BigDecimal amount, RefundChannel channel) {
        this.id = UUID.randomUUID().toString();
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.channel = channel;
        this.status = RefundStatus.REQUESTED;
        this.createdAt = Instant.now();
    }

    public void transitionTo(RefundStatus next) {
        if (!canTransition(status, next)) {
            throw new IllegalStateException("Illegal refund transition: " + status + " → " + next);
        }
        this.status = next;
        if (next == RefundStatus.SETTLED || next == RefundStatus.FAILED) {
            this.settledAt = Instant.now();
        }
    }

    private static boolean canTransition(RefundStatus from, RefundStatus to) {
        return switch (from) {
            case REQUESTED -> to == RefundStatus.PROCESSING || to == RefundStatus.FAILED;
            case PROCESSING -> to == RefundStatus.SETTLED || to == RefundStatus.FAILED;
            case SETTLED, FAILED -> false;
        };
    }

    public String getId() { return id; }
    public String getPaymentId() { return paymentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public RefundChannel getChannel() { return channel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }

    public enum RefundStatus { REQUESTED, PROCESSING, SETTLED, FAILED }
    public enum RefundChannel { ORIGINAL_CARD, BANK_TRANSFER, STORE_CREDIT }
}
