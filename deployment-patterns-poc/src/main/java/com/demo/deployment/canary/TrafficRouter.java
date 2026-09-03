package com.demo.deployment.canary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process traffic router that simulates a load balancer / service mesh
 * splitting requests across two backend versions.
 *
 *   BLUE_GREEN: 100% to the active color. Flipping `activeColor` switches
 *     all traffic instantly (the classic blue-green cutover).
 *
 *   CANARY: `canaryWeight` percent of requests go to GREEN, the rest to
 *     BLUE. Bucketing is hash(userId) so a given user is sticky.
 *
 * State is held in a single immutable Config record swapped via AtomicReference
 * so reads are lock-free.
 */
@Component
public class TrafficRouter {

    private static final Logger log = LoggerFactory.getLogger(TrafficRouter.class);

    public record Config(RoutingMode mode, String activeColor, int canaryWeight) {}

    private final AtomicReference<Config> config =
            new AtomicReference<>(new Config(RoutingMode.BLUE_GREEN, "BLUE", 0));

    private final LongAdder blueHits = new LongAdder();
    private final LongAdder greenHits = new LongAdder();

    public Backend route(String userId) {
        Config c = config.get();
        Backend chosen = switch (c.mode) {
            case BLUE_GREEN -> "GREEN".equals(c.activeColor) ? Backend.GREEN : Backend.BLUE;
            case CANARY -> {
                int bucket = Math.floorMod(("route:" + userId).hashCode(), 100);
                yield bucket < c.canaryWeight ? Backend.GREEN : Backend.BLUE;
            }
        };
        (chosen == Backend.GREEN ? greenHits : blueHits).increment();
        return chosen;
    }

    public Config config() {
        return config.get();
    }

    public Config setMode(RoutingMode mode) {
        Config updated = config.updateAndGet(c -> new Config(mode, c.activeColor, c.canaryWeight));
        log.info("routing mode -> {}", updated);
        return updated;
    }

    public Config setActiveColor(String color) {
        if (!"BLUE".equals(color) && !"GREEN".equals(color)) {
            throw new IllegalArgumentException("color must be BLUE or GREEN, got " + color);
        }
        Config updated = config.updateAndGet(c -> new Config(c.mode, color, c.canaryWeight));
        log.info("active color -> {}", updated);
        return updated;
    }

    public Config setCanaryWeight(int weight) {
        if (weight < 0 || weight > 100) {
            throw new IllegalArgumentException("weight must be 0..100, got " + weight);
        }
        Config updated = config.updateAndGet(c -> new Config(c.mode, c.activeColor, weight));
        log.info("canary weight -> {}", updated);
        return updated;
    }

    public long blueHits()  { return blueHits.sum(); }
    public long greenHits() { return greenHits.sum(); }

    public void resetCounters() {
        blueHits.reset();
        greenHits.reset();
    }
}
