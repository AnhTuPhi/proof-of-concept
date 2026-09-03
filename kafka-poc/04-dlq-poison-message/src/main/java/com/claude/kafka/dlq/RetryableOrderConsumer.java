package com.claude.kafka.dlq;

import com.claude.kafka.common.error.PoisonMessageException;
import com.claude.kafka.common.error.TransientException;
import com.claude.kafka.common.event.EventHeaders;
import com.claude.kafka.common.metrics.KafkaAppMetrics;
import com.claude.kafka.common.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Reference implementation of the "retry topic + DLQ" pattern using
 * Spring Kafka's {@link RetryableTopic}.
 * <p>
 * What this gives you that ad-hoc "sleep and retry inside the handler" does not:
 * <ul>
 *   <li><strong>Non-blocking retries.</strong> The handler returns immediately;
 *       the failed message is republished to a retry topic with a {@code dueDate}
 *       header. Another consumer picks it up after the backoff. The main topic
 *       keeps moving — one slow customer can't stall the whole partition.</li>
 *   <li><strong>Per-partition isolation broken cleanly.</strong> Retries don't
 *       block subsequent records on the same partition.</li>
 *   <li><strong>Poison-message short-circuit.</strong> Throwing a
 *       {@link PoisonMessageException} skips retries and goes straight to DLT.</li>
 *   <li><strong>DLT handler in the same class.</strong> Quarantined records
 *       are inspected/persisted by {@link #onDlt}.</li>
 * </ul>
 * Topics created (auto): {@code orders.placed.v1.retry-0, -1, -2}, {@code .dlt}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryableOrderConsumer {

    private final KafkaAppMetrics metrics;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 2_000, multiplier = 3.0, maxDelay = 60_000),
            autoCreateTopics = "true",
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            include = {TransientException.class},
            exclude = {PoisonMessageException.class},
            retryTopicSuffix = ".retry",
            dltTopicSuffix = ".dlq"
    )
    @KafkaListener(topics = Topics.ORDERS_PLACED, groupId = "orders-dlq-demo")
    public void onMessage(ConsumerRecord<String, String> record) {
        metrics.recordConsumed(record.topic());

        String value = record.value();
        log.debug("Received from {}: key={}", record.topic(), record.key());

        // Demo: trigger different failure modes by payload markers.
        // "POISON" in the payload = non-retriable, straight to DLQ.
        // "FAIL"   in the payload = retriable; eventually DLQ after 4 attempts.
        if (value != null && value.contains("POISON")) {
            throw new PoisonMessageException("Schema violation: missing required field");
        }
        if (value != null && value.contains("FAIL")) {
            throw new TransientException("Downstream API timed out");
        }
        // Normal happy path.
    }

    @DltHandler
    public void onDlt(ConsumerRecord<String, String> record) {
        metrics.recordSentToDlq(Topics.ORDERS_PLACED);

        String exClass = headerString(record, EventHeaders.EXCEPTION_CLASS);
        String exMsg   = headerString(record, EventHeaders.EXCEPTION_MESSAGE);
        log.error("DLQ landed: topic={} partition={} offset={} key={} ex={} msg={}",
                record.topic(), record.partition(), record.offset(),
                record.key(), exClass, exMsg);

        // In production, the DLQ handler should:
        //  - persist to a long-term store (S3, archive Postgres) for forensics,
        //  - emit an alert to on-call (paging on DLQ rate, not absolute count),
        //  - never re-throw - throwing here would loop the DLT.
    }

    private static String headerString(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value());
    }
}
