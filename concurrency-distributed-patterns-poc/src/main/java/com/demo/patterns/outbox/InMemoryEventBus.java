package com.demo.patterns.outbox;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stand-in for a real broker (Kafka/RabbitMQ). Keeps the last N delivered
 * messages so they're observable via the controller.
 */
@Component
public class InMemoryEventBus {

    private final ConcurrentLinkedQueue<Delivered> delivered = new ConcurrentLinkedQueue<>();
    private static final int MAX = 200;

    public void publish(OutboxEvent event) {
        delivered.add(new Delivered(
                event.getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getPayload(),
                Instant.now()
        ));
        while (delivered.size() > MAX) {
            delivered.poll();
        }
    }

    public List<Delivered> recent() {
        return List.copyOf(delivered);
    }

    public record Delivered(Long eventId, String eventType, String aggregateType,
                            String aggregateId, String payload, Instant deliveredAt) {}
}
