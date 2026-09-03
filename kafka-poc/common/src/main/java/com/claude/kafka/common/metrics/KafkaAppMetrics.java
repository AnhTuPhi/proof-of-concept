package com.claude.kafka.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Tiny facade so every POC reports the same metric names to Prometheus.
 * <p>
 * Naming follows OpenTelemetry semconv-ish style:
 * {@code kafka_app_<verb>_<unit>}. Keep cardinality low — topic is fine, key is not.
 */
@Component
public class KafkaAppMetrics {

    private final MeterRegistry registry;

    public KafkaAppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordPublished(String topic) {
        Counter.builder("kafka_app_messages_published_total")
                .tag("topic", topic).register(registry).increment();
    }

    public void recordConsumed(String topic) {
        Counter.builder("kafka_app_messages_consumed_total")
                .tag("topic", topic).register(registry).increment();
    }

    public void recordFailed(String topic, String reason) {
        Counter.builder("kafka_app_messages_failed_total")
                .tag("topic", topic).tag("reason", reason)
                .register(registry).increment();
    }

    public void recordSentToDlq(String topic) {
        Counter.builder("kafka_app_messages_dlq_total")
                .tag("topic", topic).register(registry).increment();
    }

    public void recordProcessingTime(String topic, long nanos) {
        Timer.builder("kafka_app_processing_time")
                .tag("topic", topic)
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }
}
