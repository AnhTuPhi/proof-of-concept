package com.claude.kafka.streams.windowing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafkaStreams;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.streams.windowing",
        "com.claude.kafka.common"
})
@EnableKafkaStreams
public class WindowingApp {
    public static void main(String[] args) {
        SpringApplication.run(WindowingApp.class, args);
    }
}
