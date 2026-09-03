package com.example.fintech.wallet;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private String id;

    @Column(nullable = false)
    private BigDecimal balance;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Version
    private long version;

    protected Wallet() {}

    public Wallet(String id, BigDecimal openingBalance, Currency currency) {
        this.id = id;
        this.balance = openingBalance;
        this.currency = currency;
    }

    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Wallet " + id + " has " + balance + ", cannot debit " + amount);
        }
        this.balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = balance.add(amount);
    }

    public String getId() { return id; }
    public BigDecimal getBalance() { return balance; }
    public Currency getCurrency() { return currency; }
    public long getVersion() { return version; }
}
