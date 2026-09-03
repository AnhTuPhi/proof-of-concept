package com.example.billing.dunning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DunningApplication {
    public static void main(String[] args) {
        SpringApplication.run(DunningApplication.class, args);
    }
}
