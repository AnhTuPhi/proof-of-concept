package com.example.fintech.wallet;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries", indexes = @Index(columnList = "wallet_id"))
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private String walletId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EntryType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 32)
    private String strategy;

    protected LedgerEntry() {}

    public LedgerEntry(String walletId, EntryType type, BigDecimal amount, BigDecimal balanceAfter, String strategy) {
        this.walletId = walletId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.strategy = strategy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getWalletId() { return walletId; }
    public EntryType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
    public String getStrategy() { return strategy; }

    public enum EntryType { DEBIT, CREDIT }
}
