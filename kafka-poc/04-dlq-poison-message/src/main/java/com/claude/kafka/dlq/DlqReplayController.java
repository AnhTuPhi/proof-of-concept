package com.claude.kafka.dlq;

import com.claude.kafka.common.consumer.SafeConsumerProps;
import com.claude.kafka.common.event.EventHeaders;
import com.claude.kafka.common.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Operational tooling for the DLQ.
 * <p>
 * In a production incident the on-call rarely wants to "redeploy and replay
 * everything". They want to:
 * <ol>
 *   <li>see what's in the DLQ ({@code GET /dlq/peek}),</li>
 *   <li>fix the consumer or the schema,</li>
 *   <li>selectively replay messages back to the main topic
 *       ({@code POST /dlq/replay?max=100}).</li>
 * </ol>
 * This controller is the minimum you need to do that without psql-ing into
 * {@code __consumer_offsets}.
 */
@Slf4j
@RestController
@RequestMapping("/dlq")
@RequiredArgsConstructor
public class DlqReplayController {

    private final KafkaTemplate<String, String> kafka;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrap;

    @GetMapping("/peek")
    public Object peek(@RequestParam(defaultValue = "10") int max) {
        var props = SafeConsumerProps.base(bootstrap, "dlq-peeker-" + System.currentTimeMillis(),
                "dlq-peek");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(Topics.ORDERS_PLACED_DLQ));
            ConsumerRecords<String, String> records = c.poll(Duration.ofSeconds(2));
            AtomicInteger n = new AtomicInteger();
            return records.records(Topics.ORDERS_PLACED_DLQ).stream()
                    .takeWhile(r -> n.incrementAndGet() <= max)
                    .map(r -> Map.of(
                            "key", String.valueOf(r.key()),
                            "offset", r.offset(),
                            "exception", headerString(r, EventHeaders.EXCEPTION_CLASS),
                            "msg", headerString(r, EventHeaders.EXCEPTION_MESSAGE)))
                    .toList();
        }
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestParam(defaultValue = "100") int max) {
        var props = SafeConsumerProps.base(bootstrap, "dlq-replayer", "dlq-replayer");
        int replayed = 0;
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(Topics.ORDERS_PLACED_DLQ));
            ConsumerRecords<String, String> records = c.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> r : records) {
                if (replayed >= max) break;
                var pr = new ProducerRecord<>(Topics.ORDERS_PLACED, r.key(), r.value());
                // Carry over the original event id so the idempotent consumer
                // (module 03) still dedupes correctly on replay.
                r.headers().forEach(h -> pr.headers().add(h));
                kafka.send(pr);
                replayed++;
            }
            kafka.flush();
            c.commitSync();
        }
        return Map.of("replayed", replayed);
    }

    private static String headerString(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value());
    }
}
