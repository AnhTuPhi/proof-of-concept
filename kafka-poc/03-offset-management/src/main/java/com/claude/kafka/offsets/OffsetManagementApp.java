package com.claude.kafka.offsets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.offsets",
        "com.claude.kafka.common"
})
public class OffsetManagementApp {
    public static void main(String[] args) {
        SpringApplication.run(OffsetManagementApp.class, args);
    }
}
