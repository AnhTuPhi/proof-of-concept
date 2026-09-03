package com.example.cdc.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape produced by the order-service via Debezium. JsonIgnoreProperties
 * lets us evolve the producer without breaking consumers on backwards-compatible
 * additions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
        UUID id,
        String customerId,
        String productSku,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        String status,
        Instant occurredAt
) {
}
