package com.demo.patterns.cqrses;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read model — denormalised snapshot maintained by the projector.
 * Queries hit this table; the event store is only used for replays.
 */
@Entity
@Table(name = "account_balance_view")
public class AccountBalanceView {

    @Id
    private String aggregateId;

    private long balance;

    private long lastEventVersion;

    private Instant updatedAt;

    protected AccountBalanceView() {}

    public AccountBalanceView(String aggregateId, long balance, long lastEventVersion) {
        this.aggregateId = aggregateId;
        this.balance = balance;
        this.lastEventVersion = lastEventVersion;
        this.updatedAt = Instant.now();
    }

    public void update(long balance, long version) {
        this.balance = balance;
        this.lastEventVersion = version;
        this.updatedAt = Instant.now();
    }

    public String getAggregateId() { return aggregateId; }
    public long getBalance() { return balance; }
    public long getLastEventVersion() { return lastEventVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
