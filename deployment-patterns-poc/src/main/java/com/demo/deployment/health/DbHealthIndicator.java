package com.demo.deployment.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fake DB dependency — readiness should drop when the DB is unreachable so
 * the load balancer routes traffic to other replicas. Flippable via
 * /admin/db/up=true|false for the demo.
 */
@Component("db")
public class DbHealthIndicator implements HealthIndicator {

    private final AtomicBoolean dbUp = new AtomicBoolean(true);

    public void setUp(boolean up) {
        dbUp.set(up);
    }

    public boolean isUp() {
        return dbUp.get();
    }

    @Override
    public Health health() {
        return dbUp.get()
                ? Health.up().withDetail("dialect", "postgres").build()
                : Health.down().withDetail("reason", "connection refused").build();
    }
}
