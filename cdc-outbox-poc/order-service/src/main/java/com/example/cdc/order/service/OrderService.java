package com.example.cdc.order.service;

import com.example.cdc.order.domain.Order;
import com.example.cdc.order.dto.OrderEventPayload;
import com.example.cdc.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String AGGREGATE_TYPE = "Order";

    private final OrderRepository orderRepository;
    private final OutboxEventPublisher outboxPublisher;

    public OrderService(OrderRepository orderRepository, OutboxEventPublisher outboxPublisher) {
        this.orderRepository = orderRepository;
        this.outboxPublisher = outboxPublisher;
    }

    /**
     * Creates an Order and a matching outbox event in one DB transaction.
     * If either insert fails, both are rolled back — the producer never lies
     * about what got published.
     */
    @Transactional
    public Order createOrder(String customerId, String productSku, int quantity, BigDecimal unitPrice) {
        Order order = Order.create(customerId, productSku, quantity, unitPrice);
        orderRepository.save(order);

        outboxPublisher.publish(
                AGGREGATE_TYPE,
                order.getId().toString(),
                "OrderCreated",
                OrderEventPayload.from(order)
        );

        log.info("order created id={} customerId={} total={}",
                order.getId(), order.getCustomerId(), order.getTotalAmount());
        return order;
    }

    @Transactional
    public Order markPaid(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.markPaid();

        outboxPublisher.publish(
                AGGREGATE_TYPE,
                order.getId().toString(),
                "OrderPaid",
                OrderEventPayload.from(order)
        );

        log.info("order paid id={}", order.getId());
        return order;
    }

    @Transactional
    public Order cancel(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.cancel();

        outboxPublisher.publish(
                AGGREGATE_TYPE,
                order.getId().toString(),
                "OrderCancelled",
                OrderEventPayload.from(order)
        );

        log.info("order cancelled id={}", order.getId());
        return order;
    }

    @Transactional(readOnly = true)
    public Order findById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
