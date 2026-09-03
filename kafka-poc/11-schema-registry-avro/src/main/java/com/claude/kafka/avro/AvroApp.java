package com.claude.kafka.avro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.avro",
        "com.claude.kafka.common"
})
@EnableKafka
public class AvroApp {
    public static void main(String[] args) {
        SpringApplication.run(AvroApp.class, args);
    }
}
