package vn.com.ipas.poc.flashsale;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two cheap filters that fire BEFORE the inventory layer ever sees a request:
 *
 *  1. Per-user rate limit: if a single user-id is sending >N req/sec, it's
 *     either a bot or a runaway client retry. Drop them — they were never
 *     getting a unit anyway.
 *
 *  2. Virtual queue: only the first K concurrent requests are admitted;
 *     everyone else gets a queue-position response ("you're #4523 in line").
 *     The actual oversell-safety still comes from the bucketed inventory —
 *     the queue is purely a throttle so we don't melt downstream services.
 *
 * In production both of these live at the edge (NGINX/Envoy + a Redis token
 * bucket), not in app code. Here they're inline for the simulation.
 */
public final class AdmissionGate {

    private final ConcurrentHashMap<String, Bucket> perUser = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final int maxInFlight;
    private final int perUserPerSecond;

    private final AtomicLong droppedByRate = new AtomicLong();
    private final AtomicLong droppedByQueue = new AtomicLong();

    public AdmissionGate(int maxInFlight, int perUserPerSecond) {
        this.maxInFlight = maxInFlight;
        this.perUserPerSecond = perUserPerSecond;
    }

    public Decision admit(String userId) {
        Bucket b = perUser.computeIfAbsent(userId, k -> new Bucket());
        if (!b.allow(perUserPerSecond)) {
            droppedByRate.incrementAndGet();
            return Decision.DROPPED_RATE_LIMIT;
        }
        int now = inFlight.incrementAndGet();
        if (now > maxInFlight) {
            inFlight.decrementAndGet();
            droppedByQueue.incrementAndGet();
            return Decision.QUEUED;
        }
        return Decision.ADMITTED;
    }

    public void release() {
        inFlight.decrementAndGet();
    }

    public long droppedByRate() { return droppedByRate.get(); }
    public long droppedByQueue() { return droppedByQueue.get(); }

    public enum Decision { ADMITTED, QUEUED, DROPPED_RATE_LIMIT }

    /** Single-second sliding bucket — coarse but enough to spot bots. */
    private static final class Bucket {
        private long secondEpoch;
        private int count;

        synchronized boolean allow(int perSecond) {
            long now = System.currentTimeMillis() / 1000;
            if (now != secondEpoch) {
                secondEpoch = now;
                count = 0;
            }
            if (count >= perSecond) return false;
            count++;
            return true;
        }
    }
}
