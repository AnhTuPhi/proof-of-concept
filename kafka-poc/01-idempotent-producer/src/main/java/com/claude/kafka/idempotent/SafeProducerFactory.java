package com.claude.kafka.idempotent;

import com.claude.kafka.common.producer.SafeProducerProps;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reference safe producer: idempotent + acks=all + bounded delivery timeout.
 * <p>
 * Idempotence guarantees that a message produced by this client will appear
 * <strong>exactly once</strong> on its target partition, even with retries —
 * Kafka adds a producer ID and per-partition sequence number to each batch
 * and the broker deduplicates by (PID, partition, sequence).
 * <p>
 * Important nuance: "exactly once" here is per producer session and per
 * partition. It does <em>not</em> imply consumer-side EOS; for that you also
 * need transactions and {@code isolation.level=read_committed}.
 */
@Component
public class SafeProducerFactory {

    private final String bootstrap;

    public SafeProducerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public KafkaProducer<String, String> create() {
        return new KafkaProducer<>(SafeProducerProps.base(bootstrap, "safe-producer"));
    }
}
