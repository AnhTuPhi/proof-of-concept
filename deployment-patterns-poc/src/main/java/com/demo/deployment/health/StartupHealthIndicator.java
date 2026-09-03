package com.demo.deployment.health;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup probe — reports DOWN until the app has finished its slow warmup
 * (cache preload, schema check, model load, ...). Kubernetes uses this to
 * give the pod time to boot WITHOUT triggering liveness restarts.
 */
@Component("warmup")
public class StartupHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StartupHealthIndicator.class);

    private final long warmupMs;
    private final AtomicBoolean warmedUp = new AtomicBoolean(false);
    private volatile long startedAt;

    public StartupHealthIndicator(@Value("${app.startup.warmup-ms:3000}") long warmupMs) {
        this.warmupMs = warmupMs;
    }

    @PostConstruct
    void warmup() {
        startedAt = System.currentTimeMillis();
        Thread.ofVirtual().name("warmup").start(() -> {
            try {
                log.info("warmup started, will take {}ms", warmupMs);
                Thread.sleep(warmupMs);
                warmedUp.set(true);
                log.info("warmup complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public Health health() {
        long elapsed = System.currentTimeMillis() - startedAt;
        if (warmedUp.get()) {
            return Health.up()
                    .withDetail("warmupMs", warmupMs)
                    .withDetail("elapsedMs", elapsed)
                    .build();
        }
        return Health.down()
                .withDetail("warmupMs", warmupMs)
                .withDetail("elapsedMs", elapsed)
                .withDetail("reason", "still warming up")
                .build();
    }
}
