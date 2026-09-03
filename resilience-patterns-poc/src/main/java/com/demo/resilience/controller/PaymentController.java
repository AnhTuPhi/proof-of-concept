package com.demo.resilience.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    /**
     * Pretend to charge a card. The controller knows nothing about idempotency —
     * the filter handles dedup and only invokes us for the first call.
     */
    @PostMapping
    public Map<String, Object> charge(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentId", UUID.randomUUID().toString());
        response.put("amount", request.get("amount"));
        response.put("currency", request.getOrDefault("currency", "USD"));
        response.put("status", "captured");
        return response;
    }
}
