package com.demo.deployment.shutdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drain choreography on shutdown:
 *   1. Flip readiness -> REFUSING_TRAFFIC. The LB / k8s readiness probe
 *      starts returning 503, so new requests get routed elsewhere.
 *   2. Wait `drainGraceMs` so the LB observes the new state before we
 *      stop accepting connections.
 *   3. Wait for in-flight requests to finish (Spring's `server.shutdown:
 *      graceful` handles the actual connection close, bounded by
 *      `spring.lifecycle.timeout-per-shutdown-phase`).
 */
@Component
public class GracefulShutdownListener {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownListener.class);

    private final ApplicationEventPublisher events;
    private final InFlightRequestTracker tracker;
    private final long drainGraceMs;

    public GracefulShutdownListener(ApplicationEventPublisher events,
                                    InFlightRequestTracker tracker,
                                    @Value("${app.shutdown.drain-grace-ms:2000}") long drainGraceMs) {
        this.events = events;
        this.tracker = tracker;
        this.drainGraceMs = drainGraceMs;
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        log.warn("SIGTERM received. flipping readiness=REFUSING_TRAFFIC. in-flight={}", tracker.current());
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);

        sleep(drainGraceMs);

        long deadline = System.currentTimeMillis() + 25_000;
        while (tracker.current() > 0 && System.currentTimeMillis() < deadline) {
            log.info("draining... in-flight={}", tracker.current());
            sleep(200);
        }

        int leftover = tracker.current();
        if (leftover > 0) {
            log.warn("drain timed out, {} requests still in-flight", leftover);
        } else {
            log.info("drain complete, safe to terminate");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
