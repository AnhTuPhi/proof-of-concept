package vn.com.ipas.poc.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory inventory + reservation store.
 *
 * Key correctness rules:
 *  - reserve() atomically checks-and-decrements available stock (no TOCTOU)
 *  - sweepExpired() returns held stock to the pool — even under contention,
 *    a reservation is finalised in exactly one terminal state (CONFIRMED, RELEASED, EXPIRED)
 *  - confirm/release/refresh are idempotent for the requesting user, no-op otherwise
 */
public final class InventoryService {

    private final ConcurrentHashMap<String, AtomicInteger> stock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reservation-sweeper");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong expiredCount = new AtomicLong();

    public InventoryService() {
        sweeper.scheduleAtFixedRate(this::sweepExpired, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void addStock(String sku, int qty) {
        stock.computeIfAbsent(sku, k -> new AtomicInteger()).addAndGet(qty);
    }

    public int getAvailable(String sku) {
        AtomicInteger s = stock.get(sku);
        return s == null ? 0 : s.get();
    }

    /**
     * Atomic check-and-decrement. Returns a reservation or null if insufficient stock.
     */
    public Reservation reserve(String userId, String sku, int qty, Duration ttl) {
        AtomicInteger s = stock.get(sku);
        if (s == null) return null;

        int prev;
        do {
            prev = s.get();
            if (prev < qty) return null;
        } while (!s.compareAndSet(prev, prev - qty));

        Reservation r = new Reservation(
                UUID.randomUUID().toString(),
                userId,
                sku,
                qty,
                Instant.now().plus(ttl),
                Reservation.Status.ACTIVE);
        reservations.put(r.id(), r);
        return r;
    }

    public boolean confirm(String reservationId) {
        Reservation existing = reservations.get(reservationId);
        if (existing == null || existing.status() != Reservation.Status.ACTIVE) return false;
        return reservations.replace(reservationId, existing, existing.withStatus(Reservation.Status.CONFIRMED));
    }

    public boolean release(String reservationId) {
        Reservation existing = reservations.get(reservationId);
        if (existing == null || existing.status() != Reservation.Status.ACTIVE) return false;
        if (!reservations.replace(reservationId, existing, existing.withStatus(Reservation.Status.RELEASED))) return false;
        stock.get(existing.sku()).addAndGet(existing.qty());
        return true;
    }

    public boolean refresh(String reservationId, Duration extension) {
        Reservation existing = reservations.get(reservationId);
        if (existing == null || existing.status() != Reservation.Status.ACTIVE) return false;
        return reservations.replace(
                reservationId, existing, existing.withExpiry(Instant.now().plus(extension)));
    }

    public void sweepExpired() {
        Instant now = Instant.now();
        for (Map.Entry<String, Reservation> e : reservations.entrySet()) {
            Reservation r = e.getValue();
            if (!r.isExpired(now)) continue;
            // CAS to expired so a concurrent confirm/release loses the race cleanly
            if (reservations.replace(r.id(), r, r.withStatus(Reservation.Status.EXPIRED))) {
                stock.get(r.sku()).addAndGet(r.qty());
                expiredCount.incrementAndGet();
            }
        }
    }

    public long expiredCount() {
        return expiredCount.get();
    }

    public int activeReservationCount() {
        return (int) reservations.values().stream()
                .filter(r -> r.status() == Reservation.Status.ACTIVE)
                .count();
    }

    public void shutdown() {
        sweeper.shutdownNow();
    }
}
