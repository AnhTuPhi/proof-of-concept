package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record CompletedOrder(
        String orderId,
        String userId,
        String productId,
        BigDecimal orderAmount,
        BigDecimal paidAmount,
        PaymentEvent.PaymentMethod paymentMethod,
        PaymentEvent.PaymentStatus paymentStatus,
        Instant orderedAt,
        Instant paidAt,
        long latencyMs
) {
    @JsonCreator
    public CompletedOrder(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("userId") String userId,
            @JsonProperty("productId") String productId,
            @JsonProperty("orderAmount") BigDecimal orderAmount,
            @JsonProperty("paidAmount") BigDecimal paidAmount,
            @JsonProperty("paymentMethod") PaymentEvent.PaymentMethod paymentMethod,
            @JsonProperty("paymentStatus") PaymentEvent.PaymentStatus paymentStatus,
            @JsonProperty("orderedAt") Instant orderedAt,
            @JsonProperty("paidAt") Instant paidAt,
            @JsonProperty("latencyMs") long latencyMs
    ) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.orderAmount = orderAmount;
        this.paidAmount = paidAmount;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        this.orderedAt = orderedAt;
        this.paidAt = paidAt;
        this.latencyMs = latencyMs;
    }
}
