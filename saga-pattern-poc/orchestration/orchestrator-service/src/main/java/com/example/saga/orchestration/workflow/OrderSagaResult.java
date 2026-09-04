package com.example.saga.orchestration.workflow;

public record OrderSagaResult(
        String orderId,
        String status,
        String paymentId,
        String reservationId,
        String shipmentId,
        String trackingNumber,
        String failureReason
) {
}
