package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Entity
@Table(name = "fx_payments")
public class FxPayment {

    @Id
    private String id;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency presentmentCurrency;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal presentmentAmount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency settlementCurrency;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal settlementAmount;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal lockedRate;

    @Column(nullable = false)
    private String quoteId;

    protected FxPayment() {}

    public FxPayment(Currency presentmentCurrency, BigDecimal presentmentAmount,
                     Currency settlementCurrency, BigDecimal lockedRate, String quoteId) {
        this.id = UUID.randomUUID().toString();
        this.presentmentCurrency = presentmentCurrency;
        this.presentmentAmount = presentmentAmount;
        this.settlementCurrency = settlementCurrency;
        this.lockedRate = lockedRate;
        this.quoteId = quoteId;
        this.settlementAmount = presentmentAmount.multiply(lockedRate)
                .setScale(settlementCurrency.scale(), RoundingMode.HALF_EVEN);
    }

    public String getId() { return id; }
    public Currency getPresentmentCurrency() { return presentmentCurrency; }
    public BigDecimal getPresentmentAmount() { return presentmentAmount; }
    public Currency getSettlementCurrency() { return settlementCurrency; }
    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public BigDecimal getLockedRate() { return lockedRate; }
    public String getQuoteId() { return quoteId; }
}
