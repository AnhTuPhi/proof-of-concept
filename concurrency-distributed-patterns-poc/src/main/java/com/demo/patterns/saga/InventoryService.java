package com.demo.patterns.saga;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class InventoryService {

    private final ConcurrentHashMap<String, AtomicInteger> stock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();

    public void seed(String sku, int qty) {
        stock.computeIfAbsent(sku, k -> new AtomicInteger()).set(qty);
    }

    public int available(String sku) {
        AtomicInteger s = stock.get(sku);
        return s == null ? 0 : s.get();
    }

    public String reserve(String sku, int qty) {
        AtomicInteger s = stock.computeIfAbsent(sku, k -> new AtomicInteger());
        do {
            int current = s.get();
            if (current < qty) {
                throw new InventoryUnavailableException("Not enough stock for " + sku);
            }
            if (s.compareAndSet(current, current - qty)) break;
        } while (true);
        String reservationId = "res_" + UUID.randomUUID();
        reservations.put(reservationId, new Reservation(sku, qty));
        return reservationId;
    }

    public void release(String reservationId) {
        Reservation r = reservations.remove(reservationId);
        if (r == null) return; // idempotent compensation
        stock.computeIfAbsent(r.sku, k -> new AtomicInteger()).addAndGet(r.qty);
    }

    private record Reservation(String sku, int qty) {}

    public static class InventoryUnavailableException extends RuntimeException {
        public InventoryUnavailableException(String msg) { super(msg); }
    }
}
