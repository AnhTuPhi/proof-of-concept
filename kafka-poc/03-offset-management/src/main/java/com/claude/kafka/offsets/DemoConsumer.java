package com.claude.kafka.offsets;

import com.claude.kafka.common.consumer.SafeConsumerProps;
import com.claude.kafka.common.event.EventHeaders;
import com.claude.kafka.common.metrics.KafkaAppMetrics;
import com.claude.kafka.common.topic.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single consumer worker whose offset-commit behavior is driven by
 * {@link CommitMode}. Used by the demo controller to start a worker in any
 * mode, optionally inject a crash after N records, and report stats.
 * <p>
 * To prove the failure modes, point the controller at a topic that already
 * has data:
 * <pre>
 *   POST /offsets/start?mode=AUTO&crashAfter=50
 *   POST /offsets/start?mode=SYNC_AFTER&crashAfter=50
 *   POST /offsets/start?mode=IDEMPOTENT_AFTER&crashAfter=50
 * </pre>
 * Then check Kafka UI for the consumer group: lag should drop differently for
 * each mode, and the {@code processed} counter exposed by {@code /offsets/stats}
 * shows how many records were actually handled vs. skipped.
 */
@Slf4j
public class DemoConsumer implements Runnable {

    private final String bootstrap;
    private final CommitMode mode;
    private final int crashAfter;
    private final IdempotencyStore idempotency;
    private final KafkaAppMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong skippedDuplicates = new AtomicLong();
    private final String groupId;

    public DemoConsumer(String bootstrap, CommitMode mode, int crashAfter,
                        IdempotencyStore idempotency, KafkaAppMetrics metrics) {
        this.bootstrap = bootstrap;
        this.mode = mode;
        this.crashAfter = crashAfter;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.groupId = "offsets-demo-" + mode.name().toLowerCase();
    }

    public long getConsumed() { return consumed.get(); }
    public long getSkipped() { return skippedDuplicates.get(); }
    public CommitMode getMode() { return mode; }

    public void stop() { running.set(false); }

    @Override
    public void run() {
        Map<String, Object> props = SafeConsumerProps.base(bootstrap, groupId, groupId + "-client");
        if (mode == CommitMode.AUTO) {
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
            props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1_000);
        }

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(Topics.ORDERS_PLACED));
            log.info("[{}] consumer started, group={}", mode, groupId);

            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {

                    if (mode == CommitMode.SYNC_BEFORE) {
                        // ANTI-PATTERN: commit before doing the work.
                        // If we crash on the next line, the offset moved on
                        // but the side effect never happened -> MESSAGE LOST.
                        consumer.commitSync(Map.of(
                                new org.apache.kafka.common.TopicPartition(r.topic(), r.partition()),
                                new org.apache.kafka.clients.consumer.OffsetAndMetadata(r.offset() + 1)));
                    }

                    if (crashAfter > 0 && consumed.get() == crashAfter) {
                        log.error("[{}] simulated crash at offset {}", mode, r.offset());
                        throw new RuntimeException("Simulated crash for demo");
                    }

                    process(r);

                    if (mode == CommitMode.SYNC_AFTER || mode == CommitMode.IDEMPOTENT_AFTER) {
                        // Commit per-record costs throughput but keeps the
                        // demo clear. In production batch-commit at end of poll.
                        consumer.commitSync(Map.of(
                                new org.apache.kafka.common.TopicPartition(r.topic(), r.partition()),
                                new org.apache.kafka.clients.consumer.OffsetAndMetadata(r.offset() + 1)));
                    }
                }
            }
        } catch (Exception ex) {
            log.error("[{}] consumer died: {}", mode, ex.getMessage());
        }
        log.info("[{}] consumer stopped. consumed={}, skipped={}",
                mode, consumed.get(), skippedDuplicates.get());
    }

    private void process(ConsumerRecord<String, String> r) {
        String messageId = headerOrOffset(r);
        long t0 = System.nanoTime();
        try {
            if (mode == CommitMode.IDEMPOTENT_AFTER) {
                boolean fresh = idempotency.markProcessed(messageId, r.topic(), groupId);
                if (!fresh) {
                    skippedDuplicates.incrementAndGet();
                    return;
                }
            }
            // Real side effect goes here. We just count.
            consumed.incrementAndGet();
            metrics.recordConsumed(r.topic());
        } finally {
            metrics.recordProcessingTime(r.topic(), System.nanoTime() - t0);
        }
    }

    private static String headerOrOffset(ConsumerRecord<String, String> r) {
        var h = r.headers().lastHeader(EventHeaders.EVENT_ID);
        return h != null
                ? new String(h.value(), StandardCharsets.UTF_8)
                : r.topic() + "-" + r.partition() + "-" + r.offset();
    }
}
