package com.example.saga.common.events;

import java.time.Instant;

public record ShippingScheduled(
        String eventId,
        String sagaId,
        String orderId,
        String shipmentId,
        String trackingNumber,
        Instant estimatedDelivery,
        Instant occurredAt
) implements SagaEvent {
}
