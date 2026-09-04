package com.vndirect.kstreams;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KafkaStreamsPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaStreamsPocApplication.class, args);
    }
}
