package com.demo.patterns.saga;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShippingService {

    private final ConcurrentHashMap<String, String> labels = new ConcurrentHashMap<>();

    public String createLabel(String orderId, String address) {
        if (address == null || address.isBlank()) {
            throw new ShippingFailedException("Address missing for order " + orderId);
        }
        String labelId = "lbl_" + UUID.randomUUID();
        labels.put(labelId, orderId);
        return labelId;
    }

    public void voidLabel(String labelId) {
        labels.remove(labelId);
    }

    public static class ShippingFailedException extends RuntimeException {
        public ShippingFailedException(String msg) { super(msg); }
    }
}
