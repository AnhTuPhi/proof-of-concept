package com.claude.kafka.outbox;

import com.claude.kafka.common.event.EventHeaders;
import com.claude.kafka.common.metrics.KafkaAppMetrics;
import com.claude.kafka.common.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls the outbox table for unpublished rows and ships them to Kafka.
 * <p>
 * Production-ready details:
 * <ul>
 *   <li><strong>FOR UPDATE SKIP LOCKED</strong> — multiple instances of this
 *       poller can run in parallel without stepping on each other. Oracle 12c+
 *       supports this; an older alternative is row-level UPDATE leasing with
 *       a {@code claimed_by} + {@code claimed_at}.</li>
 *   <li><strong>Bounded batch.</strong> Pulls {@code BATCH_SIZE} at a time so
 *       the transaction doesn't grow unboundedly during recovery.</li>
 *   <li><strong>Marks {@code published_at} only after the broker ack.</strong>
 *       If the publish fails, the row is retried on the next tick. Combined
 *       with idempotent producer = at-most-once duplicates, in practice none.</li>
 *   <li><strong>Use the event id as the message key header.</strong> Consumers
 *       can dedupe across retries even if the publish fires twice.</li>
 * </ul>
 * <p>
 * For Oracle, the long-term play is Debezium reading the outbox table directly
 * (zero polling overhead, true CDC). See module 12. This poller exists for
 * teams that don't have Debezium yet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final int BATCH_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaAppMetrics metrics;

    private final AtomicLong publishedTotal = new AtomicLong();

    public long getPublishedTotal() { return publishedTotal.get(); }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void flush() {
        List<OutboxEntry> batch = lockAndFetch();
        if (batch.isEmpty()) return;

        List<String> publishedIds = new ArrayList<>(batch.size());
        for (OutboxEntry e : batch) {
            try {
                String topic = topicFor(e.getEventType());
                ProducerRecord<String, String> rec = new ProducerRecord<>(
                        topic, e.getPartitionKey(), e.getPayload());
                rec.headers().add(EventHeaders.EVENT_ID,
                        e.getId().getBytes(StandardCharsets.UTF_8));
                rec.headers().add(EventHeaders.EVENT_TYPE,
                        e.getEventType().getBytes(StandardCharsets.UTF_8));
                rec.headers().add(EventHeaders.SOURCE_APP, "outbox-poller".getBytes());

                // .get() makes this synchronous within the batch. For higher
                // throughput, collect futures and await at the end of the loop.
                kafka.send(rec).get();
                publishedIds.add(e.getId());
                metrics.recordPublished(topic);
            } catch (Exception ex) {
                log.error("Publish failed for outbox id={}: {}", e.getId(), ex.getMessage());
                metrics.recordFailed("outbox", ex.getClass().getSimpleName());
                // Don't mark it published - next poll retries.
            }
        }

        if (!publishedIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(publishedIds.size(), "?"));
            jdbc.update("UPDATE appuser.outbox SET published_at = SYSTIMESTAMP " +
                            "WHERE id IN (" + placeholders + ")",
                    publishedIds.toArray());
            publishedTotal.addAndGet(publishedIds.size());
            log.debug("Marked {} outbox rows published", publishedIds.size());
        }
    }

    private List<OutboxEntry> lockAndFetch() {
        return jdbc.query(
                "SELECT id, aggregate_type, aggregate_id, event_type, payload, " +
                        "       partition_key, created_at " +
                        "FROM appuser.outbox " +
                        "WHERE published_at IS NULL " +
                        "ORDER BY created_at " +
                        "FETCH FIRST " + BATCH_SIZE + " ROWS ONLY " +
                        "FOR UPDATE SKIP LOCKED",
                (rs, i) -> OutboxEntry.builder()
                        .id(rs.getString("id"))
                        .aggregateType(rs.getString("aggregate_type"))
                        .aggregateId(rs.getString("aggregate_id"))
                        .eventType(rs.getString("event_type"))
                        .payload(rs.getString("payload"))
                        .partitionKey(rs.getString("partition_key"))
                        .createdAt(rs.getTimestamp("created_at").toInstant())
                        .build()
        );
    }

    private static String topicFor(String eventType) {
        return switch (eventType) {
            case "OrderPlaced" -> Topics.ORDERS_PLACED;
            case "OrderPaid" -> Topics.ORDERS_PAID;
            case "OrderShipped" -> Topics.ORDERS_SHIPPED;
            case "OrderCancelled" -> Topics.ORDERS_CANCELLED;
            default -> "events." + eventType.toLowerCase() + ".v1";
        };
    }
}
