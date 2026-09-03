package com.example.cdc.order.service;

import com.example.cdc.order.domain.OutboxEvent;
import com.example.cdc.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Writes events to the outbox table. MUST be called from within an existing
 * {@code @Transactional} method so both the business write and the outbox
 * insert share one DB transaction — that's the entire point of the pattern.
 *
 * No direct Kafka call here. Debezium is responsible for moving rows from
 * the WAL to Kafka, so this class never touches a broker.
 */
@Component
public class OutboxEventPublisher {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent publish(String aggregateType,
                               String aggregateId,
                               String eventType,
                               Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize outbox payload", e);
        }
        OutboxEvent event = OutboxEvent.of(aggregateType, aggregateId, eventType, json);
        return repository.save(event);
    }
}
