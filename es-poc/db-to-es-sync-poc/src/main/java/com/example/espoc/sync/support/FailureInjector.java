package com.example.espoc.sync.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets tests / HTTP headers cause specific subsystems to throw on demand.
 * Used to demonstrate the failure modes of each sync strategy.
 */
@Component
public class FailureInjector {

    public enum Target { ES, KAFKA, DB }

    private final AtomicInteger esFailRemaining = new AtomicInteger();
    private final AtomicInteger kafkaFailRemaining = new AtomicInteger();
    private final AtomicInteger dbFailRemaining = new AtomicInteger();

    public void inject(Target t, int count) {
        switch (t) {
            case ES -> esFailRemaining.addAndGet(count);
            case KAFKA -> kafkaFailRemaining.addAndGet(count);
            case DB -> dbFailRemaining.addAndGet(count);
        }
    }

    public void clear() {
        esFailRemaining.set(0);
        kafkaFailRemaining.set(0);
        dbFailRemaining.set(0);
    }

    public void maybeFail(Target t) {
        AtomicInteger counter = switch (t) {
            case ES -> esFailRemaining;
            case KAFKA -> kafkaFailRemaining;
            case DB -> dbFailRemaining;
        };
        if (counter.get() > 0 && counter.getAndDecrement() > 0) {
            throw new InjectedFailureException(t.name() + " failure injected");
        }
    }

    public int remaining(Target t) {
        return switch (t) {
            case ES -> esFailRemaining.get();
            case KAFKA -> kafkaFailRemaining.get();
            case DB -> dbFailRemaining.get();
        };
    }

    public static class InjectedFailureException extends RuntimeException {
        public InjectedFailureException(String message) { super(message); }
    }
}
