package com.demo.patterns.saga;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for a payment service. State: per-customer balance.
 * Charge debits balance, refund credits it back. Fully in-memory.
 */
@Service
public class PaymentService {

    private final ConcurrentHashMap<String, AtomicLong> balance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> charges = new ConcurrentHashMap<>();

    public void seed(String customer, long amount) {
        balance.computeIfAbsent(customer, k -> new AtomicLong()).set(amount);
    }

    public long balanceOf(String customer) {
        AtomicLong b = balance.get(customer);
        return b == null ? 0 : b.get();
    }

    /** Returns a chargeId for later refund. Throws if insufficient funds. */
    public String charge(String customer, long amount) {
        AtomicLong b = balance.computeIfAbsent(customer, k -> new AtomicLong());
        long next;
        do {
            long current = b.get();
            if (current < amount) {
                throw new PaymentFailedException("Insufficient funds for " + customer);
            }
            next = current - amount;
            if (b.compareAndSet(current, next)) break;
        } while (true);
        String chargeId = "ch_" + UUID.randomUUID();
        charges.put(chargeId, amount);
        return chargeId;
    }

    public void refund(String customer, String chargeId) {
        Long amount = charges.remove(chargeId);
        if (amount == null) return; // already refunded — idempotent compensation
        balance.computeIfAbsent(customer, k -> new AtomicLong()).addAndGet(amount);
    }

    public static class PaymentFailedException extends RuntimeException {
        public PaymentFailedException(String msg) { super(msg); }
    }
}
