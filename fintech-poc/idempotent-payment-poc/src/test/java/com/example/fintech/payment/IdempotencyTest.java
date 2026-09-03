package com.example.fintech.payment;

import com.example.fintech.common.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyTest {

    @Autowired PaymentService service;
    @Autowired PaymentRepository paymentRepo;

    @Test
    void sameKeySameBody_returnsReplayedSameId() {
        String key = "key-" + UUID.randomUUID();
        PaymentRequest req = new PaymentRequest(new BigDecimal("100000"), Currency.VND, "cust-1");

        PaymentResponse first = service.charge(key, req);
        PaymentResponse second = service.charge(key, req);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.id(), second.id());
        assertEquals(1, paymentRepo.count(),
                "only ONE payment row should exist for the same idempotency key");
    }

    @Test
    void sameKeyDifferentBody_throwsConflict() {
        String key = "key-" + UUID.randomUUID();
        service.charge(key, new PaymentRequest(new BigDecimal("100"), Currency.USD, "cust-1"));

        assertThrows(IdempotencyConflictException.class, () ->
                service.charge(key, new PaymentRequest(new BigDecimal("200"), Currency.USD, "cust-1")));
    }

    @Test
    void concurrentSameKey_onlyOnePaymentCreated() throws Exception {
        String key = "key-" + UUID.randomUUID();
        PaymentRequest req = new PaymentRequest(new BigDecimal("500"), Currency.USD, "cust-concurrent");

        long countBefore = paymentRepo.count();

        int N = 20;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        List<Callable<PaymentResponse>> tasks = IntStream.range(0, N)
                .<Callable<PaymentResponse>>mapToObj(i -> () -> service.charge(key, req))
                .collect(Collectors.toList());

        List<Future<PaymentResponse>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int success = 0;
        String singleId = null;
        for (Future<PaymentResponse> f : futures) {
            try {
                PaymentResponse r = f.get();
                if (singleId == null) singleId = r.id();
                assertEquals(singleId, r.id(), "all winners must observe the same payment id");
                success++;
            } catch (Exception ignored) {
                // IdempotencyConflictException is acceptable for racers that hit IN_FLIGHT
            }
        }

        assertTrue(success >= 1, "at least one caller must succeed");
        assertEquals(countBefore + 1, paymentRepo.count(),
                "exactly one new payment must be persisted across " + N + " concurrent attempts");
    }
}
