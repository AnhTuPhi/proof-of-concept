package vn.com.ipas.poc.oversell;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mirrors the JPA `@Version` pattern: read row + version, write back with
 * `WHERE id=? AND version=?`. If version moved, the UPDATE affects 0 rows,
 * we retry. Same CAS shape, but here we count retries to expose write
 * amplification under contention.
 */
public final class OptimisticVersionStrategy implements InventoryStrategy {
    private volatile int stock;
    private final AtomicLong version = new AtomicLong();
    private final LongAdder retries = new LongAdder();
    private final Object writeLock = new Object();

    @Override public String name() { return "Optimistic locking (@Version style)"; }
    @Override public void seed(int initial) { stock = initial; version.set(0); retries.reset(); }
    @Override public int currentStock() { return stock; }

    @Override
    public boolean tryBuy(int qty) {
        for (int attempt = 0; attempt < 100; attempt++) {
            long ver = version.get();
            int snapshot = stock;
            if (snapshot < qty) return false;

            // Mirror "UPDATE ... WHERE id=? AND version=?" — atomic CAS on (version, stock)
            synchronized (writeLock) {
                if (version.get() == ver) {
                    stock = snapshot - qty;
                    version.incrementAndGet();
                    return true;
                }
            }
            retries.increment();
        }
        return false; // give up after 100 retries — alert ops in real life
    }

    public long retryCount() { return retries.sum(); }
}
