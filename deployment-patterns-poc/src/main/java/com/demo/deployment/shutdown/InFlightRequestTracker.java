package com.demo.deployment.shutdown;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InFlightRequestTracker {

    private final AtomicInteger inFlight = new AtomicInteger(0);

    public int begin() {
        return inFlight.incrementAndGet();
    }

    public int end() {
        return inFlight.decrementAndGet();
    }

    public int current() {
        return inFlight.get();
    }
}
