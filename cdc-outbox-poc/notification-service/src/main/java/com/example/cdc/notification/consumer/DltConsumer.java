package com.example.cdc.notification.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Tails the dead-letter topic so poison messages surface in logs/metrics
 * instead of disappearing. In production this might fan out to an alerting
 * pipeline or a UI for manual triage.
 */
@Component
public class DltConsumer {

    private static final Logger log = LoggerFactory.getLogger(DltConsumer.class);

    @KafkaListener(
            topics = "${app.kafka.orders-topic}.DLT",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=java.lang.String"}
    )
    public void onDeadLetter(ConsumerRecord<String, ?> record, Acknowledgment ack) {
        String exceptionMessage = readHeader(record, "kafka_dlt-exception-message");
        String originalTopic = readHeader(record, "kafka_dlt-original-topic");
        log.error("DLT message from topic={} key={} offset={} reason={}",
                originalTopic, record.key(), record.offset(), exceptionMessage);
        ack.acknowledge();
    }

    private String readHeader(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
