package com.claude.kafka.rebalance;

import com.claude.kafka.common.consumer.SafeConsumerProps;
import com.claude.kafka.common.metrics.KafkaAppMetrics;
import com.claude.kafka.common.topic.Topics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A consumer that demonstrates three production-critical mechanisms together:
 *
 * <ol>
 *   <li><strong>Static membership.</strong> The {@code group.instance.id} means
 *       restarting this consumer (within {@code session.timeout.ms}) does NOT
 *       trigger a rebalance. The group thinks the same member rejoined.</li>
 *
 *   <li><strong>Cooperative-sticky rebalance.</strong> Partitions are revoked
 *       incrementally instead of "stop the world". Listener callback measures
 *       and logs the actual pause duration so you can graph it.</li>
 *
 *   <li><strong>Pause/resume backpressure.</strong> When the in-flight queue
 *       passes {@code highWater}, we {@code pause()} the assigned partitions
 *       so the broker stops shipping records. When it drains under
 *       {@code lowWater}, we {@code resume()}. Crucially, we still call
 *       {@code poll()} during the pause — that's what keeps us in the group
 *       (otherwise {@code max.poll.interval.ms} would kick us out).</li>
 * </ol>
 *
 * The mistake teams make: doing the work on the polling thread and not calling
 * {@code poll()} for >{@code max.poll.interval.ms}. The result is a rebalance
 * storm: every slow record causes the partition to migrate, the new owner is
 * also slow, and the cycle continues.
 */
@Slf4j
@Component
public class BackpressureConsumer implements Runnable {

    private final String bootstrap;
    private final KafkaAppMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger artificialDelayMs = new AtomicInteger(0);

    private static final int HIGH_WATER = 200;
    private static final int LOW_WATER  = 50;

    private Thread worker;
    private KafkaConsumer<String, String> consumer;

    public BackpressureConsumer(@Value("${spring.kafka.bootstrap-servers}") String bootstrap,
                                KafkaAppMetrics metrics) {
        this.bootstrap = bootstrap;
        this.metrics = metrics;
    }

    public void setDelayMs(int ms) { artificialDelayMs.set(ms); }
    public int getInFlight() { return inFlight.get(); }

    @PostConstruct public void start() {
        worker = new Thread(this, "backpressure-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy public void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
    }

    @Override
    public void run() {
        String instanceId = System.getenv().getOrDefault("HOSTNAME", "local") + "-bp";
        var props = SafeConsumerProps.withStaticMembership(
                bootstrap, "backpressure-demo", "bp-client", instanceId);

        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            this.consumer = c;
            c.subscribe(List.of(Topics.ORDERS_PLACED), new RebalanceLogger());

            boolean paused = false;
            while (running.get()) {
                ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> r : records) {
                    inFlight.incrementAndGet();
                    try {
                        // Simulate slow downstream
                        int d = artificialDelayMs.get();
                        if (d > 0) Thread.sleep(d);
                        metrics.recordConsumed(r.topic());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
                // Manual offset commit AFTER processing the whole batch
                if (!records.isEmpty()) {
                    c.commitSync();
                }

                // Backpressure: if we're falling behind, pause polling for new
                // records, but KEEP calling poll() to stay in the group.
                Set<TopicPartition> assigned = c.assignment();
                if (!paused && inFlight.get() > HIGH_WATER) {
                    c.pause(assigned);
                    paused = true;
                    log.warn("PAUSED ({} in-flight, high-water={})", inFlight.get(), HIGH_WATER);
                } else if (paused && inFlight.get() < LOW_WATER) {
                    c.resume(assigned);
                    paused = false;
                    log.info("RESUMED ({} in-flight)", inFlight.get());
                }
            }
        } catch (Exception ex) {
            log.error("Backpressure consumer died", ex);
        }
    }

    /**
     * Cooperative-sticky rebalance listener that measures the actual pause
     * duration per revocation. Graph {@code partitions_revoked_duration_ms}
     * over a deploy window and you'll see the difference vs. eager assignors.
     */
    private class RebalanceLogger implements ConsumerRebalanceListener {
        private long revokedAtNs;

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            revokedAtNs = System.nanoTime();
            log.info("Revoked: {}", partitions);
            // CRITICAL: commit before losing the partition - skipping this
            // causes duplicate processing on the new owner.
            if (consumer != null && !partitions.isEmpty()) {
                try { consumer.commitSync(); } catch (Exception ignored) {}
            }
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            long pauseMs = (System.nanoTime() - revokedAtNs) / 1_000_000;
            log.info("Assigned: {} (pause was {} ms)", partitions, pauseMs);
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            // Called when the consumer is fenced (long pause exceeded session timeout)
            log.warn("LOST partitions (likely exceeded session timeout): {}", partitions);
        }
    }
}
