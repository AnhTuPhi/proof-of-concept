package com.poc.batchingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class BatchIngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchIngestApplication.class, args);
    }
}
