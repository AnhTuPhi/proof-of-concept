package com.demo.resilience.service;

import com.demo.resilience.downstream.FlakeyDownstreamService;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Drives retries programmatically through Resilience4j's {@link RetryRegistry}.
 *
 * Why not the @Retry annotation? It works for plain controller-to-service calls,
 * but the stampede demo here calls retry-guarded code from inside the same bean —
 * that bypasses Spring's AOP proxy and silently disables the annotation.
 * Using the registry sidesteps the footgun and keeps the demo honest.
 */
@Service
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);

    private final FlakeyDownstreamService downstream;
    private final Retry retry;

    public RetryService(FlakeyDownstreamService downstream, RetryRegistry registry) {
        this.downstream = downstream;
        this.retry = registry.retry("flakeyRetry");
    }

    public String guardedCall(String tag) {
        Supplier<String> guarded = Retry.decorateSupplier(retry, () -> {
            log.info("attempting downstream call, tag={}", tag);
            return downstream.call(tag);
        });
        try {
            return guarded.get();
        } catch (Throwable t) {
            return "retry exhausted for '" + tag + "' -> " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * Fan out N concurrent retry-guarded calls on virtual threads.
     * With jitter enabled (see application.yml), retries land on randomized
     * offsets — vs. all-at-once without jitter (thundering herd).
     */
    public List<String> stampede(int concurrency) {
        List<Thread> threads = new ArrayList<>(concurrency);
        List<String> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            String tag = "client-" + i;
            threads.add(Thread.ofVirtual().start(() -> results.add(guardedCall(tag))));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return results;
    }
}
