package com.example.saga.choreography.order.service;

import com.example.saga.choreography.order.domain.Order;
import com.example.saga.choreography.order.messaging.SagaEventPublisher;
import com.example.saga.choreography.order.repository.OrderRepository;
import com.example.saga.common.dto.OrderRequest;
import com.example.saga.common.dto.OrderResponse;
import com.example.saga.common.enums.OrderStatus;
import com.example.saga.common.enums.SagaStatus;
import com.example.saga.common.events.OrderCreated;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaEventPublisher eventPublisher;

    /**
     * Persists the order in {@code PENDING}/{@code STARTED} state and emits {@link OrderCreated}
     * to kick off the choreography. The DB write commits before the event is published so the
     * order is always queryable through the REST API even if Kafka is briefly unavailable.
     */
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        String orderId = "ord-" + UUID.randomUUID();
        String sagaId = "saga-" + UUID.randomUUID();
        BigDecimal total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        Order order = Order.builder()
                .orderId(orderId)
                .sagaId(sagaId)
                .customerId(request.customerId())
                .productId(request.productId())
                .quantity(request.quantity())
                .unitPrice(request.unitPrice())
                .totalAmount(total)
                .shippingAddress(request.shippingAddress())
                .orderStatus(OrderStatus.PENDING)
                .sagaStatus(SagaStatus.STARTED)
                .createdAt(Instant.now())
                .build();

        Order saved = orderRepository.save(order);
        log.info("Created order {} with saga {}", saved.getOrderId(), saved.getSagaId());

        OrderCreated event = new OrderCreated(
                UUID.randomUUID().toString(),
                sagaId,
                orderId,
                request.customerId(),
                request.productId(),
                request.quantity(),
                request.unitPrice(),
                total,
                request.shippingAddress(),
                Instant.now());

        eventPublisher.publish(event);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderBySaga(String sagaId) {
        return orderRepository.findBySagaId(sagaId)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException("Saga not found: " + sagaId));
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getSagaId(),
                order.getCustomerId(),
                order.getProductId(),
                order.getQuantity(),
                order.getTotalAmount(),
                order.getOrderStatus(),
                order.getSagaStatus(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
