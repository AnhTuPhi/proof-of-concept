package com.example.saga.common.events;

import java.time.Instant;

public record OrderCompleted(
        String eventId,
        String sagaId,
        String orderId,
        Instant occurredAt
) implements SagaEvent {
}
