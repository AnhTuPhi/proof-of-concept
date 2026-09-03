package com.claude.kafka.txn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.txn",
        "com.claude.kafka.common"
})
public class TransactionsApp {
    public static void main(String[] args) {
        SpringApplication.run(TransactionsApp.class, args);
    }
}
