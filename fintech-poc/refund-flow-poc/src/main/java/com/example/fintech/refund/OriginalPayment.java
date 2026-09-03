package com.example.fintech.refund;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "original_payments")
public class OriginalPayment {

    @Id
    private String id;

    @Column(nullable = false)
    private BigDecimal grossAmount;

    @Column(nullable = false)
    private BigDecimal couponDiscount;

    @Column(nullable = false)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Column(nullable = false)
    private Instant capturedAt;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private PaymentMethodStatus methodStatus;

    @Column(nullable = false)
    private BigDecimal refundedAmount;

    protected OriginalPayment() {}

    public OriginalPayment(String id, BigDecimal gross, BigDecimal couponDiscount, Currency currency,
                           Instant capturedAt, PaymentMethodStatus methodStatus) {
        this.id = id;
        this.grossAmount = gross;
        this.couponDiscount = couponDiscount;
        this.paidAmount = gross.subtract(couponDiscount);
        this.currency = currency;
        this.capturedAt = capturedAt;
        this.methodStatus = methodStatus;
        this.refundedAmount = BigDecimal.ZERO;
    }

    public void recordRefund(BigDecimal amount) {
        BigDecimal next = refundedAmount.add(amount);
        if (next.compareTo(paidAmount) > 0) {
            throw new IllegalStateException(
                    "Cumulative refund " + next + " exceeds paid amount " + paidAmount);
        }
        this.refundedAmount = next;
    }

    public BigDecimal remainingRefundable() {
        return paidAmount.subtract(refundedAmount);
    }

    public String getId() { return id; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public BigDecimal getCouponDiscount() { return couponDiscount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public Currency getCurrency() { return currency; }
    public Instant getCapturedAt() { return capturedAt; }
    public PaymentMethodStatus getMethodStatus() { return methodStatus; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }

    public enum PaymentMethodStatus { ACTIVE, EXPIRED, REMOVED }
}
