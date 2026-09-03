package com.demo.resilience.downstream;

import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a downstream dependency that misbehaves on purpose:
 * fails a configurable percentage of calls and can be told to "slow down".
 * Used to drive Circuit Breaker, Retry, and Bulkhead demos.
 */
@Service
public class FlakeyDownstreamService {

    private volatile double failureRate = 0.7;
    private volatile long slowMillis = 0L;
    private final AtomicInteger callCounter = new AtomicInteger();

    public String call(String tag) {
        int n = callCounter.incrementAndGet();
        if (slowMillis > 0) {
            try {
                Thread.sleep(slowMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
        }
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new DownstreamUnavailableException("downstream blew up on call #" + n + " (" + tag + ")");
        }
        return "ok #" + n + " (" + tag + ")";
    }

    public void setFailureRate(double rate) {
        this.failureRate = Math.max(0, Math.min(1, rate));
    }

    public void setSlowMillis(long millis) {
        this.slowMillis = Math.max(0, millis);
    }

    public double failureRate() {
        return failureRate;
    }

    public long slowMillis() {
        return slowMillis;
    }
}
