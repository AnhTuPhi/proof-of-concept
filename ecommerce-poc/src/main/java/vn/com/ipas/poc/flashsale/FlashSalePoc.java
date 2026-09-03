package vn.com.ipas.poc.flashsale;

import vn.com.ipas.poc.common.Banner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * POC 3: 100k concurrent requests, 100 units, Black-Friday shape.
 *
 * Layers, from outside in:
 *  1. AdmissionGate — bot rate-limit + virtual queue. The cheapest layer.
 *  2. BucketedInventory — atomic decrement spread across N buckets so the
 *     hot key isn't actually a hot single CAS target.
 *
 * Test: prove no oversell, prove throughput, and show how many requests
 * the admission gate kept away from inventory.
 */
public final class FlashSalePoc {

    private static final int UNITS = 100;
    private static final int BUCKETS = 16;
    private static final int CONCURRENT_REQUESTS = 100_000;
    private static final int MAX_IN_FLIGHT = 5_000;
    private static final int PER_USER_PER_SECOND = 3;
    private static final int BOT_USERS = 50;
    private static final int HUMAN_USERS = 80_000;

    public static void main(String[] args) throws Exception {
        Banner.section("POC 3 — Flash sale: " + CONCURRENT_REQUESTS + " requests, " + UNITS + " units");
        System.out.printf("Bucket count: %d, max in-flight: %d, per-user limit: %d req/s%n",
                BUCKETS, MAX_IN_FLIGHT, PER_USER_PER_SECOND);
        System.out.printf("Population: %d humans + %d bots (bots spam many requests each)%n%n",
                HUMAN_USERS, BOT_USERS);

        BucketedInventory inventory = new BucketedInventory(UNITS, BUCKETS);
        AdmissionGate gate = new AdmissionGate(MAX_IN_FLIGHT, PER_USER_PER_SECOND);

        AtomicInteger sold = new AtomicInteger();
        LongAdder admittedNanos = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        // 80% of requests are humans (one buy each), 20% are bot replays
                        String userId = (idx % 5 == 0)
                                ? "bot-" + (idx % BOT_USERS)
                                : "human-" + (idx % HUMAN_USERS);

                        long t0 = System.nanoTime();
                        var decision = gate.admit(userId);
                        if (decision != AdmissionGate.Decision.ADMITTED) return;

                        try {
                            if (inventory.tryBuy()) sold.incrementAndGet();
                        } finally {
                            gate.release();
                        }
                        admittedNanos.add(System.nanoTime() - t0);
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            long t0 = System.nanoTime();
            start.countDown();
            done.await();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            int remaining = inventory.totalRemaining();
            long admitted = CONCURRENT_REQUESTS - gate.droppedByRate() - gate.droppedByQueue();

            Banner.sub("Results");
            System.out.printf("Sold:                    %,d  (expected %d)%n", sold.get(), UNITS);
            System.out.printf("Stock remaining:         %,d  (expected 0)%n", remaining);
            System.out.printf("Admitted to inventory:   %,d%n", admitted);
            System.out.printf("Dropped — rate limit:    %,d  (mostly bots)%n", gate.droppedByRate());
            System.out.printf("Dropped — queue full:    %,d  (would see 'you are #X in line')%n", gate.droppedByQueue());
            System.out.printf("Bucket walks (CAS miss): %,d  (lower = better bucket spread)%n", inventory.bucketWalkCount());
            System.out.printf("Wall time:               %,d ms%n", elapsedMs);
            if (elapsedMs > 0) {
                System.out.printf("Throughput:              %,d req/s%n",
                        CONCURRENT_REQUESTS * 1_000L / Math.max(1, elapsedMs));
            }

            boolean ok = sold.get() == UNITS && remaining == 0;
            System.out.println(ok
                    ? "PASS: no oversell, no leak"
                    : "FAIL: oversold or stock leaked");

            Banner.sub("Takeaways");
            System.out.println("- Admission gate keeps " + pct(gate.droppedByRate() + gate.droppedByQueue(), CONCURRENT_REQUESTS)
                    + " of traffic away from inventory entirely.");
            System.out.println("- " + BUCKETS + " buckets means CAS contention drops by ~" + BUCKETS + "x vs a single counter.");
            System.out.println("- Real systems also pre-warm the cache layer and use CAPTCHA + login gate before this point.");
        }
    }

    private static String pct(long part, long total) {
        return String.format("%.1f%%", 100.0 * part / total);
    }
}
