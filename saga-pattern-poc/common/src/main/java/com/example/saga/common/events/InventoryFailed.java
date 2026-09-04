package com.example.saga.common.events;

import com.example.saga.common.enums.FailureReason;

import java.time.Instant;

public record InventoryFailed(
        String eventId,
        String sagaId,
        String orderId,
        String productId,
        Integer quantity,
        FailureReason reason,
        String message,
        Instant occurredAt
) implements SagaEvent {
}
