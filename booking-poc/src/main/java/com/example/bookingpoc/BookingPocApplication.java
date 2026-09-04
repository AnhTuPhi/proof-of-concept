package com.example.bookingpoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BookingPocApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingPocApplication.class, args);
    }
}
