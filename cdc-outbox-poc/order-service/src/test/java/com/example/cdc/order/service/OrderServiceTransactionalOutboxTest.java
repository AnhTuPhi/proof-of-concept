package com.example.cdc.order.service;

import com.example.cdc.order.domain.Order;
import com.example.cdc.order.repository.OrderRepository;
import com.example.cdc.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@DirtiesContext
class OrderServiceTransactionalOutboxTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cdc")
            .withUsername("cdc")
            .withPassword("cdc")
            .withCommand("postgres", "-c", "wal_level=logical");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OutboxEventRepository outboxRepository;

    @BeforeEach
    void cleanup() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void createOrder_writesOrderAndOutboxRowInOneTransaction() {
        Order order = orderService.createOrder("cust-1", "SKU-1", 3, new BigDecimal("9.99"));

        assertThat(orderRepository.findById(order.getId())).isPresent();

        var events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        var event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(order.getId().toString());
        assertThat(event.getEventType()).isEqualTo("OrderCreated");
        assertThat(event.getPayload())
                .contains(order.getId().toString())
                .contains("\"customerId\":\"cust-1\"")
                .contains("\"productSku\":\"SKU-1\"")
                .contains("\"quantity\":3");
    }

    @Test
    void payAndCancel_emitOutboxEventsPerStateChange() {
        Order created = orderService.createOrder("cust-2", "SKU-2", 1, new BigDecimal("10.00"));
        orderService.markPaid(created.getId());

        var events = outboxRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting("eventType")
                .containsExactlyInAnyOrder("OrderCreated", "OrderPaid");
    }
}
