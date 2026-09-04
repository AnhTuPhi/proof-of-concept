package com.example.saga.choreography.order.messaging;

import com.example.saga.common.KafkaTopics;
import com.example.saga.common.events.SagaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Single egress point for saga events.
 * Keying by {@code sagaId} guarantees all events for the same saga land on the
 * same partition, which in turn guarantees in-order delivery to consumers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CompletableFuture<?> publish(SagaEvent event) {
        log.info("Publishing event {} for saga {}", event.getClass().getSimpleName(), event.sagaId());
        return kafkaTemplate.send(KafkaTopics.SAGA_EVENTS, event.sagaId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event {} for saga {}",
                                event.getClass().getSimpleName(), event.sagaId(), ex);
                    }
                });
    }
}
