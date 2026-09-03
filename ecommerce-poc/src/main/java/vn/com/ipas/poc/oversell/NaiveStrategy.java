package vn.com.ipas.poc.oversell;

/**
 * The bug everyone writes the first time: check, then decrement.
 * Between the read and the write, anything can happen.
 */
public final class NaiveStrategy implements InventoryStrategy {
    private int stock;

    @Override public String name() { return "Naive (read-then-write)"; }
    @Override public void seed(int initial) { this.stock = initial; }
    @Override public int currentStock() { return stock; }

    @Override
    public boolean tryBuy(int qty) {
        if (stock >= qty) {
            // Yield a fraction of a slice so the bug shows up more reliably.
            // Real-world equivalent: any work between read and write — a DB
            // round-trip, a log call, a network read.
            Thread.yield();
            stock -= qty;
            return true;
        }
        return false;
    }
}
