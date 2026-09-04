package com.vndirect.kstreams.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private Kafka kafka = new Kafka();
    private Topics topics = new Topics();
    private Demo demo = new Demo();

    @Data
    public static class Kafka {
        private String bootstrapServers = "localhost:9093";
        private String applicationId = "kafka-streams-poc";
        private String stateDir = "./data/kafka-streams";
        private int numStreamThreads = 2;
        private short replicationFactor = 1;
        private long commitIntervalMs = 1000;
        private long cacheMaxBytesBuffering = 10485760;
        private String processingGuarantee = "at_least_once";
        private String autoOffsetReset = "earliest";
        private int maxPollRecords = 500;
        private int requestTimeoutMs = 60000;
        private int retryBackoffMs = 1000;
    }

    @Data
    public static class Topics {
        private String orders = "orders.v1";
        private String payments = "payments.v1";
        private String products = "products.v1";
        private String users = "users.v1";
        private String enrichedOrders = "enriched-orders.v1";
        private String completedOrders = "completed-orders.v1";
        private String categoryRevenue = "category-revenue.v1";
        private String userOrderCounts = "user-order-counts.v1";
        private String userSessions = "user-sessions.v1";
        private String dlq = "streams.dlq.v1";
        private int partitions = 3;
    }

    @Data
    public static class Demo {
        private boolean autoGenerate = true;
        private long intervalMs = 1500;
    }
}
