package com.demo.resilience.controller;

import com.demo.resilience.ratelimit.RateLimiters;
import com.demo.resilience.ratelimit.SlidingWindowLog;
import com.demo.resilience.ratelimit.TokenBucket;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/ratelimit")
public class RateLimitController {

    private final RateLimiters registry;

    public RateLimitController(RateLimiters registry) {
        this.registry = registry;
    }

    @GetMapping("/token-bucket")
    public ResponseEntity<Map<String, Object>> tokenBucket(
            @RequestHeader(value = "X-Client-Id", defaultValue = "anon") String clientId) {
        TokenBucket bucket = registry.bucketFor(clientId);
        boolean allowed = bucket.tryAcquire();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("allowed", allowed);
        body.put("tokensRemaining", String.format("%.2f", bucket.availableTokens()));
        return ResponseEntity
                .status(allowed ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
                .body(body);
    }

    @GetMapping("/sliding-window")
    public ResponseEntity<Map<String, Object>> slidingWindow(
            @RequestHeader(value = "X-Client-Id", defaultValue = "anon") String clientId) {
        SlidingWindowLog window = registry.windowFor(clientId);
        boolean allowed = window.tryAcquire();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("allowed", allowed);
        body.put("currentCount", window.currentCount());
        return ResponseEntity
                .status(allowed ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
                .body(body);
    }
}
