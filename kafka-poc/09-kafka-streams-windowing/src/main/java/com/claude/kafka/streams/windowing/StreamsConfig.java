package com.claude.kafka.streams.windowing;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Streams config worth calling out:
 * <ul>
 *   <li>{@code processing.guarantee=exactly_once_v2} — broker-level EOS. Each
 *       record's read offset commit and any downstream produce are atomic.
 *       Requires Kafka 2.5+ brokers (we're on 4.x, no issue).</li>
 *   <li>{@code num.standby.replicas=1} — for state stores. Cuts recovery time
 *       from minutes to seconds when a node dies.</li>
 *   <li>{@code commit.interval.ms=5000} with EOS — bounds the size of any
 *       single transaction.</li>
 * </ul>
 */
@Configuration
public class StreamsConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrap;

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig() {
        Map<String, Object> p = new HashMap<>();
        p.put(org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG, "clickstream-windowing");
        p.put(org.apache.kafka.streams.StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(org.apache.kafka.streams.StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        p.put(org.apache.kafka.streams.StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        p.put(org.apache.kafka.streams.StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
                org.apache.kafka.streams.StreamsConfig.EXACTLY_ONCE_V2);
        p.put(org.apache.kafka.streams.StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 1);
        p.put(org.apache.kafka.streams.StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5_000);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaStreamsConfiguration(p);
    }
}
