package com.demo.resilience.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BulkheadService {

    private static final Logger log = LoggerFactory.getLogger(BulkheadService.class);

    /**
     * Service A — protected by its own semaphore bulkhead.
     * If too many threads pile up here, callers get rejected fast
     * instead of dragging the rest of the system down.
     */
    @Bulkhead(name = "serviceA", fallbackMethod = "fallback")
    public String callA(long workMillis) {
        log.info("serviceA working for {}ms on {}", workMillis, Thread.currentThread());
        sleep(workMillis);
        return "A:" + Thread.currentThread().getName();
    }

    /**
     * Service B — its own bulkhead. Even when A is fully saturated,
     * B still serves traffic because it has independent capacity.
     */
    @Bulkhead(name = "serviceB", fallbackMethod = "fallback")
    public String callB(long workMillis) {
        log.info("serviceB working for {}ms on {}", workMillis, Thread.currentThread());
        sleep(workMillis);
        return "B:" + Thread.currentThread().getName();
    }

    @SuppressWarnings("unused")
    private String fallback(long workMillis, Throwable t) {
        return "rejected by bulkhead: " + t.getClass().getSimpleName();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
