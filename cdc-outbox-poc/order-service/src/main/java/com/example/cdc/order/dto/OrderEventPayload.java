package com.example.cdc.order.dto;

import com.example.cdc.order.domain.Order;
import com.example.cdc.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shape of the JSON written into outbox_events.payload. This becomes the Kafka
 * message body via Debezium's Outbox Event Router SMT. Keep it stable —
 * consumers depend on this contract.
 */
public record OrderEventPayload(
        UUID id,
        String customerId,
        String productSku,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        OrderStatus status,
        Instant occurredAt
) {
    public static OrderEventPayload from(Order order) {
        return new OrderEventPayload(
                order.getId(),
                order.getCustomerId(),
                order.getProductSku(),
                order.getQuantity(),
                order.getUnitPrice(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getUpdatedAt()
        );
    }
}
