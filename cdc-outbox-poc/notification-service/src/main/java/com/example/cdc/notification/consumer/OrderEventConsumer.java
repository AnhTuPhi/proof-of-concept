package com.example.cdc.notification.consumer;

import com.example.cdc.notification.dto.OrderEvent;
import com.example.cdc.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Subscribes to the topic published by Debezium's Outbox Event Router SMT.
 *
 * Important Debezium headers:
 *   id       — the outbox row UUID (used as our dedup key)
 *   type     — the event_type column (OrderCreated / OrderPaid / OrderCancelled)
 *   key      — the aggregate_id (already present as the record key)
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private static final String HEADER_ID = "id";
    private static final String HEADER_TYPE = "type";

    private final NotificationService notificationService;

    public OrderEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = "${app.kafka.orders-topic}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, OrderEvent> record, Acknowledgment ack) {
        UUID eventId = readUuidHeader(record, HEADER_ID);
        String eventType = readStringHeader(record, HEADER_TYPE);

        if (eventId == null || eventType == null) {
            log.error("missing required outbox headers (id/type) on record key={} offset={}",
                    record.key(), record.offset());
            ack.acknowledge();
            return;
        }

        log.debug("received eventId={} type={} key={} partition={} offset={}",
                eventId, eventType, record.key(), record.partition(), record.offset());

        notificationService.handle(eventId, eventType, record.value());
        ack.acknowledge();
    }

    private UUID readUuidHeader(ConsumerRecord<?, ?> record, String name) {
        String raw = readStringHeader(record, name);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("invalid UUID in header {}: {}", name, raw);
            return null;
        }
    }

    private String readStringHeader(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
