package com.example.billing.proration;

import com.example.billing.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

/**
 * Per-customer credit balance. We do NOT refund cash; downgrades become credit
 * that gets consumed by the next charge.
 */
@Entity
@Table(name = "customer_credits")
public class CustomerCredit {

    @Id
    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    private Long version;

    protected CustomerCredit() {
    }

    public CustomerCredit(String customerId, String currency) {
        this.customerId = customerId;
        this.currency = currency;
        this.balance = BigDecimal.ZERO;
    }

    public void add(Money amount) {
        requireCurrency(amount);
        this.balance = this.balance.add(amount.amount());
    }

    /**
     * Consume up to {@code requested}. Returns how much credit was actually applied.
     */
    public Money consumeUpTo(Money requested) {
        requireCurrency(requested);
        if (balance.signum() <= 0 || requested.isZero() || requested.isNegative()) {
            return Money.zero(currency);
        }
        BigDecimal applied = balance.min(requested.amount());
        balance = balance.subtract(applied);
        return Money.of(applied, currency);
    }

    public Money balance() {
        return Money.of(balance, currency);
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    private void requireCurrency(Money money) {
        if (!money.currencyCode().equals(currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: balance " + currency + " vs " + money.currencyCode());
        }
    }
}
