package com.example.saga.common.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Marker interface for every event flowing through the choreography saga.
 * The {@code type} discriminator is serialized into JSON so consumers can route
 * polymorphic payloads without reflection.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OrderCreated.class, name = "ORDER_CREATED"),
        @JsonSubTypes.Type(value = PaymentCompleted.class, name = "PAYMENT_COMPLETED"),
        @JsonSubTypes.Type(value = PaymentFailed.class, name = "PAYMENT_FAILED"),
        @JsonSubTypes.Type(value = PaymentRefunded.class, name = "PAYMENT_REFUNDED"),
        @JsonSubTypes.Type(value = InventoryReserved.class, name = "INVENTORY_RESERVED"),
        @JsonSubTypes.Type(value = InventoryFailed.class, name = "INVENTORY_FAILED"),
        @JsonSubTypes.Type(value = InventoryReleased.class, name = "INVENTORY_RELEASED"),
        @JsonSubTypes.Type(value = ShippingScheduled.class, name = "SHIPPING_SCHEDULED"),
        @JsonSubTypes.Type(value = ShippingFailed.class, name = "SHIPPING_FAILED"),
        @JsonSubTypes.Type(value = OrderCompleted.class, name = "ORDER_COMPLETED"),
        @JsonSubTypes.Type(value = OrderCancelled.class, name = "ORDER_CANCELLED")
})
public sealed interface SagaEvent
        permits OrderCreated, PaymentCompleted, PaymentFailed, PaymentRefunded,
        InventoryReserved, InventoryFailed, InventoryReleased,
        ShippingScheduled, ShippingFailed, OrderCompleted, OrderCancelled {

    String eventId();

    String sagaId();

    String orderId();

    Instant occurredAt();
}
