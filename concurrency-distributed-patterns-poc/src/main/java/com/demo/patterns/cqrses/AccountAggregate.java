package com.demo.patterns.cqrses;

import java.util.List;

/**
 * Pure in-memory aggregate. State is derived by replaying events.
 *
 * Commands check invariants and return new events; events advance state.
 * This separation is what makes the model auditable — the events are the
 * source of truth, the aggregate's fields are a cache.
 */
public class AccountAggregate {

    private String id;
    private long version;
    private long balance;
    private boolean open;

    public static AccountAggregate replay(String id, List<AccountEvent> history) {
        AccountAggregate a = new AccountAggregate();
        a.id = id;
        for (AccountEvent e : history) {
            a.apply(e);
        }
        return a;
    }

    public long version() { return version; }
    public long balance() { return balance; }
    public boolean isOpen() { return open; }

    public AccountEvent open(long initialDeposit) {
        if (open) throw new IllegalStateException("Account " + id + " already open");
        if (initialDeposit < 0) throw new IllegalArgumentException("initialDeposit < 0");
        String payload = "{\"initial\":" + initialDeposit + "}";
        return new AccountEvent(id, version + 1, "AccountOpened", payload);
    }

    public AccountEvent deposit(long amount) {
        ensureOpen();
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        return new AccountEvent(id, version + 1, "MoneyDeposited", "{\"amount\":" + amount + "}");
    }

    public AccountEvent withdraw(long amount) {
        ensureOpen();
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (balance < amount) throw new IllegalStateException("Insufficient balance: have " + balance + ", want " + amount);
        return new AccountEvent(id, version + 1, "MoneyWithdrawn", "{\"amount\":" + amount + "}");
    }

    public void apply(AccountEvent event) {
        switch (event.getType()) {
            case "AccountOpened" -> {
                open = true;
                balance = parseAmount(event.getPayload(), "initial");
            }
            case "MoneyDeposited" -> balance += parseAmount(event.getPayload(), "amount");
            case "MoneyWithdrawn" -> balance -= parseAmount(event.getPayload(), "amount");
            default -> throw new IllegalStateException("Unknown event " + event.getType());
        }
        version = event.getVersion();
    }

    private void ensureOpen() {
        if (!open) throw new IllegalStateException("Account " + id + " is not open");
    }

    private static long parseAmount(String payload, String field) {
        int start = payload.indexOf("\"" + field + "\":") + field.length() + 3;
        int end = payload.indexOf('}', start);
        return Long.parseLong(payload.substring(start, end).trim());
    }
}
