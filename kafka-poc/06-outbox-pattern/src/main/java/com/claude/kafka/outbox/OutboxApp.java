package com.claude.kafka.outbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.outbox",
        "com.claude.kafka.common"
})
@EnableScheduling
@EnableTransactionManagement
public class OutboxApp {
    public static void main(String[] args) {
        SpringApplication.run(OutboxApp.class, args);
    }
}
