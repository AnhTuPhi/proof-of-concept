package com.example.saga.choreography.shipping.messaging;

import com.example.saga.common.KafkaTopics;
import com.example.saga.common.events.SagaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(SagaEvent event) {
        log.info("Publishing {} for saga {}", event.getClass().getSimpleName(), event.sagaId());
        kafkaTemplate.send(KafkaTopics.SAGA_EVENTS, event.sagaId(), event);
    }
}
