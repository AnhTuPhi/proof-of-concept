package com.demo.resilience.service;

import com.demo.resilience.downstream.FlakeyDownstreamService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

@Service
public class CircuitBreakerService {

    private final FlakeyDownstreamService downstream;

    public CircuitBreakerService(FlakeyDownstreamService downstream) {
        this.downstream = downstream;
    }

    @CircuitBreaker(name = "flakey", fallbackMethod = "fallback")
    public String guardedCall() {
        return downstream.call("cb");
    }

    @SuppressWarnings("unused")
    private String fallback(Throwable t) {
        return "fallback: circuit open or downstream failed -> " + t.getClass().getSimpleName();
    }
}
