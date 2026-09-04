package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record EnrichedOrder(
        String orderId,
        String userId,
        String userDisplayName,
        String userTier,
        String country,
        String productId,
        String productName,
        String category,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        Instant orderedAt
) {
    @JsonCreator
    public EnrichedOrder(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("userId") String userId,
            @JsonProperty("userDisplayName") String userDisplayName,
            @JsonProperty("userTier") String userTier,
            @JsonProperty("country") String country,
            @JsonProperty("productId") String productId,
            @JsonProperty("productName") String productName,
            @JsonProperty("category") String category,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("unitPrice") BigDecimal unitPrice,
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("orderedAt") Instant orderedAt
    ) {
        this.orderId = orderId;
        this.userId = userId;
        this.userDisplayName = userDisplayName;
        this.userTier = userTier;
        this.country = country;
        this.productId = productId;
        this.productName = productName;
        this.category = category;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.orderedAt = orderedAt;
    }
}
