package com.example.espoc.sync.config;

import com.example.espoc.sync.strategy.outbox.OutboxPoller;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic outboxProductsTopic() {
        // 3 partitions: per-aggregate key gives ordering within a partition.
        return new NewTopic(OutboxPoller.TOPIC, 3, (short) 1);
    }
}
