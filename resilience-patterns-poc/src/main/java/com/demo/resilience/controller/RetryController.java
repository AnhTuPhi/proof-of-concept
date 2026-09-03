package com.demo.resilience.controller;

import com.demo.resilience.service.RetryService;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/retry")
public class RetryController {

    private final RetryService service;
    private final RetryRegistry registry;

    public RetryController(RetryService service, RetryRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @GetMapping("/call")
    public String call(@RequestParam(defaultValue = "single") String tag) {
        return service.guardedCall(tag);
    }

    @GetMapping("/stampede")
    public List<String> stampede(@RequestParam(defaultValue = "10") int concurrency) {
        return service.stampede(concurrency);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Retry r = registry.retry("flakeyRetry");
        Retry.Metrics m = r.getMetrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("successWithoutRetry", m.getNumberOfSuccessfulCallsWithoutRetryAttempt());
        out.put("successWithRetry", m.getNumberOfSuccessfulCallsWithRetryAttempt());
        out.put("failureWithoutRetry", m.getNumberOfFailedCallsWithoutRetryAttempt());
        out.put("failureWithRetry", m.getNumberOfFailedCallsWithRetryAttempt());
        return out;
    }
}
