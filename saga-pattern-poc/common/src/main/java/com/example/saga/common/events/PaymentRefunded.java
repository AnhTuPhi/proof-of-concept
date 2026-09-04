package com.example.saga.common.events;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentRefunded(
        String eventId,
        String sagaId,
        String orderId,
        String paymentId,
        String customerId,
        BigDecimal amount,
        Instant occurredAt
) implements SagaEvent {
}
