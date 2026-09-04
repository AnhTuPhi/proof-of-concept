package com.example.saga.common.events;

import com.example.saga.common.enums.FailureReason;

import java.time.Instant;

public record ShippingFailed(
        String eventId,
        String sagaId,
        String orderId,
        FailureReason reason,
        String message,
        Instant occurredAt
) implements SagaEvent {
}
