package com.example.saga.common.events;

import java.time.Instant;

public record InventoryReserved(
        String eventId,
        String sagaId,
        String orderId,
        String productId,
        Integer quantity,
        String reservationId,
        Instant occurredAt
) implements SagaEvent {
}
