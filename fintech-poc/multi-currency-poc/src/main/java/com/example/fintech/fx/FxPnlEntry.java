package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fx_pnl_entries")
public class FxPnlEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String referenceId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant occurredAt;

    protected FxPnlEntry() {}

    public FxPnlEntry(String referenceId, String kind, Currency currency, BigDecimal amount) {
        this.referenceId = referenceId;
        this.kind = kind;
        this.currency = currency;
        this.amount = amount;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getReferenceId() { return referenceId; }
    public String getKind() { return kind; }
    public Currency getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public Instant getOccurredAt() { return occurredAt; }
}
