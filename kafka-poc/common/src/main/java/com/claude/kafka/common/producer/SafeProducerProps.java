package com.claude.kafka.common.producer;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Producer config that prefers <strong>correctness over throughput</strong>.
 * <p>
 * Notable choices:
 * <ul>
 *   <li>{@code acks=all} + {@code enable.idempotence=true} = no duplicates,
 *       no silent loss on leader failure. This pair is the floor for any
 *       producer worth running.</li>
 *   <li>{@code max.in.flight.requests.per.connection=5} is the upper bound
 *       the idempotent producer guarantees ordering for.</li>
 *   <li>{@code retries=Integer.MAX_VALUE} + bounded {@code delivery.timeout.ms}
 *       lets the client retry transparently while still failing fast at the
 *       application layer.</li>
 *   <li>{@code compression.type=zstd} — best ratio:CPU trade-off in 2025;
 *       supported by Kafka 4.x. Use {@code lz4} for older brokers.</li>
 * </ul>
 *
 * For throughput-first producers (telemetry, clickstreams) tune {@code linger.ms}
 * and {@code batch.size} upward at the call site rather than relaxing safety.
 */
public final class SafeProducerProps {
    private SafeProducerProps() {}

    public static Map<String, Object> base(String bootstrap, String clientId) {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Durability + exactly-once delivery guarantees from the broker's POV
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        p.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // Bounded retry windows - actual end-to-end SLO knob
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);

        // Throughput tuning that doesn't sacrifice safety
        p.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64L * 1024 * 1024);

        // Hot-partition mitigation for keyless or low-cardinality keys
        p.put(ProducerConfig.PARTITIONER_IGNORE_KEYS_CONFIG, false);

        return p;
    }

    public static Map<String, Object> transactional(String bootstrap, String clientId, String txnId) {
        Map<String, Object> p = base(bootstrap, clientId);
        p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, txnId);
        p.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 60_000);
        return p;
    }
}
