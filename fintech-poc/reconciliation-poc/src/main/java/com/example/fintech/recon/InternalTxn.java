package com.example.fintech.recon;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "internal_txns",
        indexes = { @Index(columnList = "provider_ref"), @Index(columnList = "matched") })
public class InternalTxn {

    @Id
    private String id;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Column(name = "provider_ref", nullable = false)
    private String providerRef;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private boolean matched;

    protected InternalTxn() {}

    public InternalTxn(String id, BigDecimal amount, Currency currency, String providerRef, Instant occurredAt) {
        this.id = id;
        this.amount = amount;
        this.currency = currency;
        this.providerRef = providerRef;
        this.occurredAt = occurredAt;
        this.matched = false;
    }

    public void markMatched() { this.matched = true; }

    public String getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public String getProviderRef() { return providerRef; }
    public Instant getOccurredAt() { return occurredAt; }
    public boolean isMatched() { return matched; }
}
