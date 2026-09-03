package com.example.fintech.payment;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private String id;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() {}

    public static Payment create(BigDecimal amount, Currency currency, String customerId) {
        Payment p = new Payment();
        p.id = UUID.randomUUID().toString();
        p.amount = amount;
        p.currency = currency;
        p.customerId = customerId;
        p.status = PaymentStatus.PENDING;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    public void transitionTo(PaymentStatus next) {
        if (!canTransition(status, next)) {
            throw new IllegalStateException("Illegal transition: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    private static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return switch (from) {
            case PENDING -> to == PaymentStatus.AUTHORIZED || to == PaymentStatus.FAILED;
            case AUTHORIZED -> to == PaymentStatus.CAPTURED || to == PaymentStatus.FAILED;
            case CAPTURED -> to == PaymentStatus.SETTLED;
            case SETTLED, FAILED -> false;
        };
    }

    public String getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getCustomerId() { return customerId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
