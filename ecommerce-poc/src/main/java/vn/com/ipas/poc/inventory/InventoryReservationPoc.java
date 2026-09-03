package vn.com.ipas.poc.inventory;

import vn.com.ipas.poc.common.Banner;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * POC 1: Inventory Reservation with TTL.
 *
 * Three scenarios:
 *   1. Happy path — reserve, confirm. Stock stays committed.
 *   2. Abandonment — reserve, walk away. TTL sweep returns stock.
 *   3. Flash sale — 1000 concurrent users, 1 unit of stock. Exactly 1 wins.
 */
public final class InventoryReservationPoc {

    public static void main(String[] args) throws Exception {
        InventoryService svc = new InventoryService();
        try {
            scenarioHappyPath(svc);
            scenarioAbandonment(svc);
            scenarioFlashSale(svc);
        } finally {
            svc.shutdown();
        }
    }

    private static void scenarioHappyPath(InventoryService svc) {
        Banner.section("Scenario 1 — Happy path: reserve then confirm");
        svc.addStock("IPHONE-15", 10);
        System.out.println("Initial stock: " + svc.getAvailable("IPHONE-15"));

        var r = svc.reserve("user-1", "IPHONE-15", 2, Duration.ofMinutes(10));
        System.out.println("Reserved 2: id=" + r.id().substring(0, 8) + " ... status=" + r.status());
        System.out.println("Available after reserve: " + svc.getAvailable("IPHONE-15"));

        boolean confirmed = svc.confirm(r.id());
        System.out.println("confirm() -> " + confirmed);
        System.out.println("Available after confirm: " + svc.getAvailable("IPHONE-15"));
        System.out.println("  (stock stays decremented — user owns the units)");
    }

    private static void scenarioAbandonment(InventoryService svc) throws Exception {
        Banner.section("Scenario 2 — Abandonment: cart walks away, TTL sweeps it back");
        svc.addStock("RTX-5090", 5);
        System.out.println("Initial stock: " + svc.getAvailable("RTX-5090"));

        var r = svc.reserve("user-2", "RTX-5090", 3, Duration.ofMillis(800));
        System.out.println("Reserved 3 with TTL=800ms: available=" + svc.getAvailable("RTX-5090"));
        System.out.println("Active reservations: " + svc.activeReservationCount());

        System.out.println("Waiting 1500ms for the sweeper ...");
        Thread.sleep(1_500);

        System.out.println("Available after sweep: " + svc.getAvailable("RTX-5090"));
        System.out.println("Active reservations: " + svc.activeReservationCount());
        System.out.println("Expired (lifetime): " + svc.expiredCount());
        System.out.println("  (unattended cart returned to the pool — no manual cleanup)");
    }

    private static void scenarioFlashSale(InventoryService svc) throws Exception {
        Banner.section("Scenario 3 — Flash sale: 1000 users, 1 unit, exactly 1 winner");
        svc.addStock("LIMITED-NFT", 1);

        int users = 1_000;
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < users; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        var r = svc.reserve("user-" + idx, "LIMITED-NFT", 1, Duration.ofSeconds(30));
                        if (r != null) {
                            winners.incrementAndGet();
                            svc.confirm(r.id());
                        } else {
                            losers.incrementAndGet();
                        }
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

            System.out.println("Winners: " + winners.get() + " (expected 1)");
            System.out.println("Losers:  " + losers.get() + " (expected 999)");
            System.out.println("Final stock: " + svc.getAvailable("LIMITED-NFT") + " (expected 0)");
            System.out.println("Elapsed: " + elapsedMs + " ms across " + users + " virtual threads");
            if (winners.get() == 1 && svc.getAvailable("LIMITED-NFT") == 0) {
                System.out.println("PASS: no oversell, no lost stock under concurrent contention");
            } else {
                System.out.println("FAIL: concurrency bug");
            }
        }
    }
}
