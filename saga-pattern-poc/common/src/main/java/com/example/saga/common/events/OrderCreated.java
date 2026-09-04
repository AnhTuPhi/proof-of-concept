package com.example.saga.common.events;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCreated(
        String eventId,
        String sagaId,
        String orderId,
        String customerId,
        String productId,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        String shippingAddress,
        Instant occurredAt
) implements SagaEvent {
}
