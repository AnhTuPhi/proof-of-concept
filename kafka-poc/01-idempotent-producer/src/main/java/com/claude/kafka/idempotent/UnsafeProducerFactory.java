package com.claude.kafka.idempotent;

import com.claude.kafka.common.producer.SafeProducerProps;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Deliberately broken producer used as a control group.
 * <p>
 * What's wrong with this config:
 * <ul>
 *   <li>{@code acks=1} — leader writes locally and acks before replicating.
 *       If the leader dies before the replicas catch up, the message is lost.</li>
 *   <li>{@code enable.idempotence=false} — duplicates on retry.</li>
 *   <li>{@code retries>0} with {@code max.in.flight=5} — out-of-order writes.</li>
 * </ul>
 * Used by the demo to <em>prove</em> these failure modes exist with traffic.
 */
@Component
public class UnsafeProducerFactory {

    private final String bootstrap;

    public UnsafeProducerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public KafkaProducer<String, String> create() {
        Map<String, Object> p = SafeProducerProps.base(bootstrap, "unsafe-producer");
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        p.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new KafkaProducer<>(p);
    }
}
