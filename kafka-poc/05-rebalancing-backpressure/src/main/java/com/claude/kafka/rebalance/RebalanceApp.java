package com.claude.kafka.rebalance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.rebalance",
        "com.claude.kafka.common"
})
public class RebalanceApp {
    public static void main(String[] args) {
        SpringApplication.run(RebalanceApp.class, args);
    }
}
