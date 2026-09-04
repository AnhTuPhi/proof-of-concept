package com.example.espoc.sync.strategy.outbox;

import com.example.espoc.sync.model.OutboxEvent;
import com.example.espoc.sync.repository.OutboxEventRepository;
import com.example.espoc.sync.support.FailureInjector;
import com.example.espoc.sync.support.FailureInjector.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the outbox table, publishes pending events to Kafka, marks them published.
 *
 * <p>Single-instance correctness: SELECT ... FOR UPDATE SKIP LOCKED makes this safe to run on multiple
 * pollers concurrently — but in this POC we run a single instance to keep things readable. For a real
 * multi-instance deploy, wrap the poll in a {@code pg_try_advisory_lock(N)} as the very first call.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    public static final String TOPIC = "outbox.products";

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafka;
    private final FailureInjector failures;

    @Value("${app.sync.outbox.batch-size:100}")
    private int batchSize;

    private final AtomicBoolean paused = new AtomicBoolean(false);

    public OutboxPoller(OutboxEventRepository outboxRepo, KafkaTemplate<String, String> kafka, FailureInjector failures) {
        this.outboxRepo = outboxRepo;
        this.kafka = kafka;
        this.failures = failures;
    }

    public void pause()  { paused.set(true);  log.warn("Outbox poller paused"); }
    public void resume() { paused.set(false); log.warn("Outbox poller resumed"); }
    public boolean isPaused() { return paused.get(); }

    @Scheduled(fixedDelayString = "${app.sync.outbox.poll-interval-ms:500}")
    @Transactional
    public void poll() {
        if (paused.get()) return;
        List<OutboxEvent> batch = outboxRepo.pickPending(batchSize);
        if (batch.isEmpty()) return;

        log.debug("Outbox poller picked {} events", batch.size());
        for (OutboxEvent ev : batch) {
            try {
                failures.maybeFail(Target.KAFKA);
                // Key by aggregate id → per-aggregate ordering on the consumer side (within a partition).
                kafka.send(TOPIC, ev.getAggregateId(), ev.getPayload()).get();
                ev.setPublishedAt(Instant.now());
                ev.setLastError(null);
            } catch (Exception e) {
                ev.setAttempts(ev.getAttempts() + 1);
                ev.setLastError(e.getMessage());
                log.warn("Outbox publish failed for event {} (attempt {}): {}", ev.getId(), ev.getAttempts(), e.toString());
            }
            ev.setPickedUpAt(Instant.now());
        }
        outboxRepo.saveAll(batch);
    }
}
