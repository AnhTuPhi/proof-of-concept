package com.claude.kafka.dlq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.context.annotation.Configuration;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.dlq",
        "com.claude.kafka.common"
})
@EnableKafka
public class DlqApp {
    public static void main(String[] args) {
        SpringApplication.run(DlqApp.class, args);
    }

    /**
     * Required to use {@code @RetryableTopic} on Spring Kafka 3.x.
     */
    @Configuration
    static class RetryTopicSupport extends RetryTopicConfigurationSupport {}
}
