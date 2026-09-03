package com.example.billing.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary value. Always rounded to the currency's default fraction digits
 * using HALF_UP. Arithmetic on Money objects of different currencies is rejected.
 */
public final class Money implements Comparable<Money> {

    public static final String DEFAULT_CURRENCY = "USD";

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.currency = Objects.requireNonNull(currency, "currency");
        int scale = currency.getDefaultFractionDigits();
        this.amount = amount.setScale(scale, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public static Money usd(String amount) {
        return of(new BigDecimal(amount), DEFAULT_CURRENCY);
    }

    public static Money usd(double amount) {
        return of(BigDecimal.valueOf(amount), DEFAULT_CURRENCY);
    }

    public static Money zero(String currencyCode) {
        return of(BigDecimal.ZERO, currencyCode);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money multiply(double factor) {
        return multiply(BigDecimal.valueOf(factor));
    }

    /**
     * Prorate this amount by elapsed/total fraction. Uses 8 digits of internal precision
     * before rounding to the currency scale, so daily proration of small amounts stays accurate.
     */
    public Money prorate(long elapsedUnits, long totalUnits) {
        if (totalUnits <= 0) {
            throw new IllegalArgumentException("totalUnits must be positive, got " + totalUnits);
        }
        BigDecimal fraction = BigDecimal.valueOf(elapsedUnits)
                .divide(BigDecimal.valueOf(totalUnits), 8, RoundingMode.HALF_UP);
        return new Money(amount.multiply(fraction), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money o) {
        requireSameCurrency(o);
        return amount.compareTo(o.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return amount.compareTo(m.amount) == 0 && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return currency.getSymbol() + amount.toPlainString();
    }
}
