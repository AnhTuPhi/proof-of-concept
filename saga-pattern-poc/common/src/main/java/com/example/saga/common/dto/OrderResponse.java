package com.example.saga.common.dto;

import com.example.saga.common.enums.OrderStatus;
import com.example.saga.common.enums.SagaStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        String orderId,
        String sagaId,
        String customerId,
        String productId,
        Integer quantity,
        BigDecimal totalAmount,
        OrderStatus orderStatus,
        SagaStatus sagaStatus,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
