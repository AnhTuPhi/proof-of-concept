package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentEvent(
        String paymentId,
        String orderId,
        BigDecimal amount,
        PaymentMethod method,
        PaymentStatus status,
        Instant paidAt
) {
    @JsonCreator
    public PaymentEvent(
            @JsonProperty("paymentId") String paymentId,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("method") PaymentMethod method,
            @JsonProperty("status") PaymentStatus status,
            @JsonProperty("paidAt") Instant paidAt
    ) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.paidAt = paidAt;
    }

    public enum PaymentMethod { CARD, BANK_TRANSFER, EWALLET, COD }

    public enum PaymentStatus { PENDING, APPROVED, DECLINED, REFUNDED }
}
