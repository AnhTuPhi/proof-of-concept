package com.demo.resilience.controller;

import com.demo.resilience.downstream.FlakeyDownstreamService;
import com.demo.resilience.service.CircuitBreakerService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/circuit")
public class CircuitBreakerController {

    private final CircuitBreakerService service;
    private final CircuitBreakerRegistry registry;
    private final FlakeyDownstreamService downstream;

    public CircuitBreakerController(CircuitBreakerService service,
                                    CircuitBreakerRegistry registry,
                                    FlakeyDownstreamService downstream) {
        this.service = service;
        this.registry = registry;
        this.downstream = downstream;
    }

    @GetMapping("/call")
    public String call() {
        return service.guardedCall();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        CircuitBreaker cb = registry.circuitBreaker("flakey");
        CircuitBreaker.Metrics m = cb.getMetrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", cb.getState().toString());
        out.put("failureRate", m.getFailureRate());
        out.put("slowCallRate", m.getSlowCallRate());
        out.put("bufferedCalls", m.getNumberOfBufferedCalls());
        out.put("failedCalls", m.getNumberOfFailedCalls());
        out.put("successfulCalls", m.getNumberOfSuccessfulCalls());
        out.put("downstreamFailureRate", downstream.failureRate());
        return out;
    }

    @GetMapping("/tune")
    public String tune(@RequestParam(defaultValue = "0.7") double failureRate) {
        downstream.setFailureRate(failureRate);
        return "downstream failureRate=" + downstream.failureRate();
    }
}
