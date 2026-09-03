package vn.com.ipas.poc.oversell;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free compare-and-set loop. Same logic as Redis Lua / a single SQL
 * `UPDATE ... SET stock = stock - ? WHERE stock >= ?`. Scales well under
 * contention because losing CAS threads just retry — no thread parking.
 */
public final class AtomicCasStrategy implements InventoryStrategy {
    private final AtomicInteger stock = new AtomicInteger();

    @Override public String name() { return "Atomic CAS loop"; }
    @Override public void seed(int initial) { stock.set(initial); }
    @Override public int currentStock() { return stock.get(); }

    @Override
    public boolean tryBuy(int qty) {
        int prev;
        do {
            prev = stock.get();
            if (prev < qty) return false;
        } while (!stock.compareAndSet(prev, prev - qty));
        return true;
    }
}
