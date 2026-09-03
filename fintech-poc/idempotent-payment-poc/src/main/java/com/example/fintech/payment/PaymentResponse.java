package com.example.fintech.payment;

import com.example.fintech.common.Currency;

import java.math.BigDecimal;

public record PaymentResponse(
        String id,
        BigDecimal amount,
        Currency currency,
        PaymentStatus status,
        String customerId,
        boolean replayed) {

    public static PaymentResponse from(Payment p, boolean replayed) {
        return new PaymentResponse(p.getId(), p.getAmount(), p.getCurrency(), p.getStatus(), p.getCustomerId(), replayed);
    }
}
