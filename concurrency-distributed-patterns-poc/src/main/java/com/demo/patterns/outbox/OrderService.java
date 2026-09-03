package com.demo.patterns.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demonstrates the Transactional Outbox pattern.
 *
 * The order and its outbox event are written in a SINGLE local transaction.
 * That guarantees atomicity between business state and the "I owe a message"
 * record — no dual-write inconsistency. A separate relay reads the outbox
 * and ships the message to the broker, marking it processed on success.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OutboxEventRepository outbox;

    public OrderService(OrderRepository orders, OutboxEventRepository outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    public OrderEntity placeOrder(String customer, String product, int quantity) {
        OrderEntity order = orders.save(new OrderEntity(customer, product, quantity));

        String payload = """
                {"orderId":%d,"customer":"%s","product":"%s","quantity":%d}"""
                .formatted(order.getId(), escape(customer), escape(product), quantity);

        outbox.save(new OutboxEvent(
                "Order",
                order.getId().toString(),
                "OrderPlaced",
                payload
        ));
        return order;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
