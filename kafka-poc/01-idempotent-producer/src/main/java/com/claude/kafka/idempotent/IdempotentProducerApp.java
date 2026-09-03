package com.claude.kafka.idempotent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.idempotent",
        "com.claude.kafka.common"
})
public class IdempotentProducerApp {
    public static void main(String[] args) {
        SpringApplication.run(IdempotentProducerApp.class, args);
    }
}
