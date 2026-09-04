package com.example.saga.common.events;

import com.example.saga.common.enums.FailureReason;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentFailed(
        String eventId,
        String sagaId,
        String orderId,
        String customerId,
        BigDecimal amount,
        FailureReason reason,
        String message,
        Instant occurredAt
) implements SagaEvent {
}
