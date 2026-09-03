package com.claude.kafka.idempotent;

import com.claude.kafka.common.event.DomainEvent;
import com.claude.kafka.common.event.EventHeaders;
import com.claude.kafka.common.metrics.KafkaAppMetrics;
import com.claude.kafka.common.topic.Topics;
import com.claude.kafka.common.util.JsonCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Endpoints:
 * <pre>
 *   POST /demo/safe?count=1000      - publish via idempotent producer
 *   POST /demo/unsafe?count=1000    - publish via the broken producer
 *   POST /demo/ordering?count=10    - prove ordering preservation with idempotent producer
 * </pre>
 * Run a consumer (module 03) against {@code orders.placed.v1} and compare the
 * delivered counts to {@code count}. With the unsafe producer you'll see
 * duplicates whenever the broker hiccups; with the safe producer the count
 * matches every time.
 */
@Slf4j
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoController {

    private final SafeProducerFactory safeFactory;
    private final UnsafeProducerFactory unsafeFactory;
    private final KafkaAppMetrics metrics;

    @PostMapping("/safe")
    public Map<String, Object> publishSafe(@RequestParam(defaultValue = "1000") int count) {
        try (KafkaProducer<String, String> p = safeFactory.create()) {
            return publishBatch(p, count, "safe");
        }
    }

    @PostMapping("/unsafe")
    public Map<String, Object> publishUnsafe(@RequestParam(defaultValue = "1000") int count) {
        try (KafkaProducer<String, String> p = unsafeFactory.create()) {
            return publishBatch(p, count, "unsafe");
        }
    }

    /**
     * Sends N records with the SAME partition key to prove that the idempotent
     * producer preserves order, even with up to 5 in-flight requests and retries.
     */
    @PostMapping("/ordering")
    public Map<String, Object> orderingDemo(@RequestParam(defaultValue = "100") int count) {
        AtomicInteger seq = new AtomicInteger();
        try (KafkaProducer<String, String> p = safeFactory.create()) {
            String aggregateId = "order-ordering-demo";
            for (int i = 0; i < count; i++) {
                DomainEvent<Map<String, Object>> e = DomainEvent.of(
                        "OrderPlaced", "Order", aggregateId,
                        Map.of("step", seq.incrementAndGet(), "amount", i));
                ProducerRecord<String, String> r = new ProducerRecord<>(
                        Topics.ORDERS_PLACED, aggregateId, JsonCodec.toJson(e));
                r.headers().add(new RecordHeader(EventHeaders.EVENT_ID,
                        e.getEventId().getBytes(StandardCharsets.UTF_8)));
                p.send(r);
            }
            p.flush();
        }
        return Map.of("sent", count, "key", "order-ordering-demo");
    }

    private Map<String, Object> publishBatch(KafkaProducer<String, String> producer,
                                             int count, String mode) {
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String key = "order-" + (i % 100);
            DomainEvent<Map<String, Object>> e = DomainEvent.of(
                    "OrderPlaced", "Order", key,
                    Map.of("amount", 100 + i, "currency", "USD"));
            ProducerRecord<String, String> r = new ProducerRecord<>(
                    Topics.ORDERS_PLACED, key, JsonCodec.toJson(e));
            r.headers().add(new RecordHeader(EventHeaders.EVENT_ID,
                    e.getEventId().getBytes(StandardCharsets.UTF_8)));
            r.headers().add(new RecordHeader("producer-mode",
                    mode.getBytes(StandardCharsets.UTF_8)));
            producer.send(r, (md, ex) -> {
                if (ex != null) {
                    log.error("Send failed [{}]: {}", mode, ex.getMessage());
                    metrics.recordFailed(Topics.ORDERS_PLACED, ex.getClass().getSimpleName());
                } else {
                    metrics.recordPublished(Topics.ORDERS_PLACED);
                }
            });
        }
        producer.flush();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("Published {} messages in {} ms via {} producer", count, elapsedMs, mode);
        return Map.of("mode", mode, "sent", count, "elapsedMs", elapsedMs);
    }
}
