package com.demo.deployment.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fake downstream service — readiness-only check. Liveness should NOT depend
 * on downstreams: if your peer is down, restarting yourself doesn't help.
 */
@Component("downstream")
public class DownstreamHealthIndicator implements HealthIndicator {

    private final AtomicBoolean up = new AtomicBoolean(true);

    public void setUp(boolean up) {
        this.up.set(up);
    }

    public boolean isUp() {
        return up.get();
    }

    @Override
    public Health health() {
        return up.get()
                ? Health.up().withDetail("name", "payments-api").build()
                : Health.down().withDetail("reason", "5xx rate > 50%").build();
    }
}
