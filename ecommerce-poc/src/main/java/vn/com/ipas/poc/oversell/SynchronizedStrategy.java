package vn.com.ipas.poc.oversell;

/**
 * Correct, simple, slow at scale: one global monitor serialises everything.
 * Throughput plateaus around lock contention; fine for low QPS, dies in flash sales.
 */
public final class SynchronizedStrategy implements InventoryStrategy {
    private int stock;
    private final Object lock = new Object();

    @Override public String name() { return "Synchronized (intrinsic lock)"; }
    @Override public void seed(int initial) { synchronized (lock) { this.stock = initial; } }
    @Override public int currentStock() { synchronized (lock) { return stock; } }

    @Override
    public boolean tryBuy(int qty) {
        synchronized (lock) {
            if (stock >= qty) {
                stock -= qty;
                return true;
            }
            return false;
        }
    }
}
