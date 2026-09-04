package com.vndirect.kstreams.config;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

@Configuration
public class TopicConfig {

    private final AppProperties props;

    public TopicConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        return new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers()
        ));
    }

    @Bean
    public NewTopic ordersTopic() {
        return topic(props.getTopics().getOrders());
    }

    @Bean
    public NewTopic paymentsTopic() {
        return topic(props.getTopics().getPayments());
    }

    @Bean
    public NewTopic productsTopic() {
        return TopicBuilder.name(props.getTopics().getProducts())
                .partitions(props.getTopics().getPartitions())
                .replicas(props.getKafka().getReplicationFactor())
                .config("cleanup.policy", "compact")
                .build();
    }

    @Bean
    public NewTopic usersTopic() {
        return TopicBuilder.name(props.getTopics().getUsers())
                .partitions(props.getTopics().getPartitions())
                .replicas(props.getKafka().getReplicationFactor())
                .config("cleanup.policy", "compact")
                .build();
    }

    @Bean
    public NewTopic enrichedOrdersTopic() {
        return topic(props.getTopics().getEnrichedOrders());
    }

    @Bean
    public NewTopic completedOrdersTopic() {
        return topic(props.getTopics().getCompletedOrders());
    }

    @Bean
    public NewTopic categoryRevenueTopic() {
        return topic(props.getTopics().getCategoryRevenue());
    }

    @Bean
    public NewTopic userOrderCountsTopic() {
        return topic(props.getTopics().getUserOrderCounts());
    }

    @Bean
    public NewTopic userSessionsTopic() {
        return topic(props.getTopics().getUserSessions());
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(props.getTopics().getDlq())
                .partitions(props.getTopics().getPartitions())
                .replicas(props.getKafka().getReplicationFactor())
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(props.getTopics().getPartitions())
                .replicas(props.getKafka().getReplicationFactor())
                .build();
    }
}
