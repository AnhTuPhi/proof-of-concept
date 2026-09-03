package vn.com.ipas.poc.oversell;

import vn.com.ipas.poc.common.Banner;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * POC 2: Race 1000 virtual threads buying 1 unit each against an initial
 * stock of 10. Every strategy except the naive one should sell exactly 10.
 * The naive one demonstrates the classic TOCTOU oversell.
 */
public final class OversellPreventionPoc {

    private static final int INITIAL_STOCK = 10;
    private static final int CONCURRENT_BUYERS = 1_000;

    public static void main(String[] args) throws Exception {
        Banner.section("POC 2 — Oversell prevention under concurrent load");
        System.out.printf("Initial stock = %d, concurrent buyers = %d, each buys 1 unit%n",
                INITIAL_STOCK, CONCURRENT_BUYERS);
        System.out.println();
        System.out.printf("%-44s %8s %10s %8s%n", "Strategy", "Sold", "Stock left", "Verdict");
        System.out.println("-".repeat(72));

        QueueSerializedStrategy queue = new QueueSerializedStrategy();
        OptimisticVersionStrategy optimistic = new OptimisticVersionStrategy();
        List<InventoryStrategy> strategies = List.of(
                new NaiveStrategy(),
                new SynchronizedStrategy(),
                new AtomicCasStrategy(),
                optimistic,
                queue
        );

        for (InventoryStrategy s : strategies) {
            run(s);
        }

        System.out.println();
        System.out.println("Optimistic-locking retries observed: " + optimistic.retryCount()
                + "  (these are wasted DB round-trips on a hot row)");
        queue.close();

        Banner.sub("Takeaways");
        System.out.println("- Naive almost always oversells once threads interleave between the check and the decrement.");
        System.out.println("- Synchronized is correct, but every buyer parks → throughput collapses on hot SKUs.");
        System.out.println("- Atomic CAS is correct and scales: losing threads spin briefly instead of parking.");
        System.out.println("- Optimistic locking is correct, but write amplification (retries) hurts hot rows.");
        System.out.println("- Queue-serialised: lowest contention, easiest to add business rules; latency dominated by queue depth.");
    }

    private static void run(InventoryStrategy s) throws Exception {
        s.seed(INITIAL_STOCK);
        AtomicInteger sold = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_BUYERS);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_BUYERS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (s.tryBuy(1)) sold.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }

        int left = s.currentStock();
        boolean correct = sold.get() == INITIAL_STOCK && left == 0;
        boolean oversold = sold.get() > INITIAL_STOCK || left < 0;
        String verdict = oversold ? "OVERSOLD" : (correct ? "OK" : "UNDERSOLD");
        System.out.printf("%-44s %8d %10d %8s%n", s.name(), sold.get(), left, verdict);
    }
}
