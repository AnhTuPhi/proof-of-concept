package com.example.cdc.notification.service;

import com.example.cdc.notification.dto.OrderEvent;
import com.example.cdc.notification.repository.ProcessedEventRepository;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=PLAINTEXT://localhost:1",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@DirtiesContext
class NotificationServiceIdempotencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notifications")
            .withUsername("notif")
            .withPassword("notif");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private NotificationService notificationService;
    @Autowired private ProcessedEventRepository processedRepository;

    @BeforeEach
    void cleanup() {
        processedRepository.deleteAll();
    }

    @Test
    void duplicateEventIsHandledIdempotently() {
        UUID eventId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(
                UUID.randomUUID(),
                "cust-1",
                "SKU-1",
                1,
                new BigDecimal("9.99"),
                new BigDecimal("9.99"),
                "PENDING",
                Instant.now()
        );

        notificationService.handle(eventId, "OrderCreated", event);
        notificationService.handle(eventId, "OrderCreated", event);
        notificationService.handle(eventId, "OrderCreated", event);

        assertThat(processedRepository.count()).isEqualTo(1L);
    }

    @Test
    void distinctEventIdsAreAllProcessed() {
        OrderEvent event = new OrderEvent(
                UUID.randomUUID(),
                "cust-1",
                "SKU-1",
                1,
                new BigDecimal("9.99"),
                new BigDecimal("9.99"),
                "PENDING",
                Instant.now()
        );

        notificationService.handle(UUID.randomUUID(), "OrderCreated", event);
        notificationService.handle(UUID.randomUUID(), "OrderPaid", event);

        assertThat(processedRepository.count()).isEqualTo(2L);
    }
}
