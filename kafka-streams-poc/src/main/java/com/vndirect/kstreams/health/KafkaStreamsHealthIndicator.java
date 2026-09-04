package com.vndirect.kstreams.health;

import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

@Component("kafkaStreamsApp")
public class KafkaStreamsHealthIndicator implements HealthIndicator {

    private final StreamsBuilderFactoryBean factoryBean;

    public KafkaStreamsHealthIndicator(StreamsBuilderFactoryBean factoryBean) {
        this.factoryBean = factoryBean;
    }

    @Override
    public Health health() {
        KafkaStreams streams = factoryBean.getKafkaStreams();
        if (streams == null) {
            return Health.down().withDetail("reason", "KafkaStreams not initialized").build();
        }
        KafkaStreams.State state = streams.state();
        Health.Builder builder = state.isRunningOrRebalancing() ? Health.up() : Health.down();
        return builder
                .withDetail("state", state.name())
                .withDetail("threads", streams.metadataForLocalThreads().size())
                .build();
    }
}
