package com.example.saga.choreography.order.config;

import com.example.saga.common.KafkaTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Wires the retry + DLT error handler used by every {@code @KafkaListener} in the service.
 *
 * <p>Each failed delivery is retried with capped exponential backoff. Once the backoff
 * elapses the record is published to {@code saga.events.DLT} so the listener never blocks
 * the partition while leaving a forensic trail.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(KafkaTopics.SAGA_EVENTS_DLT, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxElapsedTime(60_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Autowired
    public void configureContainerFactory(
            ConcurrentKafkaListenerContainerFactory<String, Object> factory,
            DefaultErrorHandler errorHandler) {
        factory.setCommonErrorHandler(errorHandler);
    }
}
