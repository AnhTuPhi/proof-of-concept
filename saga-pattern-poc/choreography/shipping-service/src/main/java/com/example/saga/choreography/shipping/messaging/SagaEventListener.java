package com.example.saga.choreography.shipping.messaging;

import com.example.saga.choreography.shipping.service.ShippingService;
import com.example.saga.common.KafkaTopics;
import com.example.saga.common.events.SagaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventListener {

    private final ShippingService shippingService;

    @KafkaListener(topics = KafkaTopics.SAGA_EVENTS, groupId = "${spring.kafka.consumer.group-id}")
    public void onEvent(SagaEvent event, Acknowledgment ack) {
        try {
            shippingService.handle(event);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Failed to handle {} for saga {}",
                    event.getClass().getSimpleName(), event.sagaId(), ex);
            throw ex;
        }
    }
}
