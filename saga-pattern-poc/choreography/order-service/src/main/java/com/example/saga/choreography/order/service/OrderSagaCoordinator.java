package com.example.saga.choreography.order.service;

import com.example.saga.choreography.order.domain.Order;
import com.example.saga.choreography.order.domain.ProcessedEvent;
import com.example.saga.choreography.order.repository.OrderRepository;
import com.example.saga.choreography.order.repository.ProcessedEventRepository;
import com.example.saga.common.enums.OrderStatus;
import com.example.saga.common.enums.SagaStatus;
import com.example.saga.common.events.InventoryFailed;
import com.example.saga.common.events.InventoryReleased;
import com.example.saga.common.events.InventoryReserved;
import com.example.saga.common.events.OrderCancelled;
import com.example.saga.common.events.OrderCompleted;
import com.example.saga.common.events.PaymentCompleted;
import com.example.saga.common.events.PaymentFailed;
import com.example.saga.common.events.PaymentRefunded;
import com.example.saga.common.events.SagaEvent;
import com.example.saga.common.events.ShippingFailed;
import com.example.saga.common.events.ShippingScheduled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Drives the order-side state machine in response to events emitted by other services.
 *
 * <p>The flow is intentionally explicit: each event handler maps to a single state
 * transition and either records a terminal state or emits the next compensating event.
 * Compensation chains run "back up" the stack — shipping failure → release inventory →
 * refund payment → cancel order — so each downstream service only needs to know its own
 * counter-operation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaCoordinator {

    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void handle(SagaEvent event) {
        MDC.put("sagaId", event.sagaId());
        try {
            if (processedEventRepository.existsById(event.eventId())) {
                log.debug("Skipping duplicate event {}", event.eventId());
                return;
            }

            Order order = orderRepository.findBySagaId(event.sagaId()).orElse(null);
            if (order == null) {
                log.warn("No order found for saga {} (event {}) — likely a different saga's event",
                        event.sagaId(), event.getClass().getSimpleName());
                return;
            }

            switch (event) {
                case PaymentCompleted e   -> onPaymentCompleted(order, e);
                case PaymentFailed e      -> onPaymentFailed(order, e);
                case PaymentRefunded e    -> onPaymentRefunded(order, e);
                case InventoryReserved e  -> onInventoryReserved(order, e);
                case InventoryFailed e    -> onInventoryFailed(order, e);
                case InventoryReleased e  -> onInventoryReleased(order, e);
                case ShippingScheduled e  -> onShippingScheduled(order, e);
                case ShippingFailed e     -> onShippingFailed(order, e);
                case OrderCompleted ignored -> log.debug("Ignoring self-emitted OrderCompleted");
                case OrderCancelled ignored -> log.debug("Ignoring self-emitted OrderCancelled");
                default -> log.debug("Order service does not handle {}", event.getClass().getSimpleName());
            }

            markProcessed(event);
        } finally {
            MDC.remove("sagaId");
        }
    }

    private void onPaymentCompleted(Order order, PaymentCompleted e) {
        log.info("Payment completed for order {} amount {}", order.getOrderId(), e.amount());
        order.setOrderStatus(OrderStatus.PAID);
        order.setSagaStatus(SagaStatus.PAYMENT_COMPLETED);
        orderRepository.save(order);
    }

    private void onInventoryReserved(Order order, InventoryReserved e) {
        log.info("Inventory reserved for order {} reservation {}", order.getOrderId(), e.reservationId());
        order.setOrderStatus(OrderStatus.INVENTORY_RESERVED);
        order.setSagaStatus(SagaStatus.INVENTORY_RESERVED);
        orderRepository.save(order);
    }

    private void onShippingScheduled(Order order, ShippingScheduled e) {
        log.info("Shipping scheduled for order {} tracking {}", order.getOrderId(), e.trackingNumber());
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setSagaStatus(SagaStatus.COMPLETED);
        orderRepository.save(order);
    }

    // ----- failure / compensation handlers -----

    private void onPaymentFailed(Order order, PaymentFailed e) {
        log.warn("Payment failed for order {}: {}", order.getOrderId(), e.message());
        cancelOrder(order, e.reason().name(), e.message());
    }

    private void onInventoryFailed(Order order, InventoryFailed e) {
        log.warn("Inventory failed for order {}: {}. Awaiting payment refund.", order.getOrderId(), e.message());
        order.setSagaStatus(SagaStatus.COMPENSATING);
        order.setFailureReason(e.reason() + ": " + e.message());
        orderRepository.save(order);
    }

    private void onShippingFailed(Order order, ShippingFailed e) {
        log.warn("Shipping failed for order {}: {}. Awaiting inventory release + payment refund.",
                order.getOrderId(), e.message());
        order.setSagaStatus(SagaStatus.COMPENSATING);
        order.setFailureReason(e.reason() + ": " + e.message());
        orderRepository.save(order);
    }

    private void onPaymentRefunded(Order order, PaymentRefunded e) {
        log.info("Payment refunded for order {}", order.getOrderId());
        cancelOrder(order, order.getFailureReason(), "Payment refunded");
    }

    private void onInventoryReleased(Order order, InventoryReleased e) {
        log.info("Inventory released for order {}. Compensation chain continues toward payment.",
                order.getOrderId());
        // payment-service is responsible for emitting PaymentRefunded after this,
        // so we just record progress and wait for the next event.
    }

    private void cancelOrder(Order order, String reason, String message) {
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setSagaStatus(SagaStatus.COMPENSATED);
        order.setFailureReason(reason + (message != null ? ": " + message : ""));
        orderRepository.save(order);
        log.info("Order {} cancelled. Saga {} compensated.", order.getOrderId(), order.getSagaId());
    }

    private void markProcessed(SagaEvent event) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId() != null ? event.eventId() : UUID.randomUUID().toString())
                .sagaId(event.sagaId())
                .eventType(event.getClass().getSimpleName())
                .processedAt(Instant.now())
                .build());
    }
}
