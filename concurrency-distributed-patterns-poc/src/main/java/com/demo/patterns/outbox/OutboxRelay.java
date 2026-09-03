package com.demo.patterns.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the outbox table on a fixed interval (the "relay") and ships each
 * event to the bus, then marks it processed. At-least-once delivery —
 * downstream consumers must dedupe by (aggregateType, aggregateId, eventId).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final InMemoryEventBus bus;
    private final int batchSize;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public OutboxRelay(OutboxEventRepository outbox,
                       InMemoryEventBus bus,
                       @Value("${demo.outbox.batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.bus = bus;
        this.batchSize = batchSize;
    }

    public void setPaused(boolean paused) {
        this.paused.set(paused);
        log.info("Outbox relay paused={}", paused);
    }

    public boolean isPaused() {
        return paused.get();
    }

    @Scheduled(fixedDelayString = "${demo.outbox.poll-interval-ms:1000}")
    @Transactional
    public void poll() {
        if (paused.get()) return;

        List<OutboxEvent> batch = outbox.findUnprocessed(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        for (OutboxEvent e : batch) {
            try {
                e.incrementAttempts();
                bus.publish(e);
                e.markProcessed();
                log.info("Relayed outbox event id={} type={}", e.getId(), e.getEventType());
            } catch (RuntimeException ex) {
                log.warn("Failed to relay outbox event id={}: {}", e.getId(), ex.toString());
            }
        }
    }
}
