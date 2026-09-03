package vn.com.ipas.poc.oversell;

public interface InventoryStrategy {
    String name();
    void seed(int initial);
    boolean tryBuy(int qty);
    int currentStock();
    /** Total time spent inside tryBuy across all callers (nanoseconds). */
    default long cumulativeNanos() { return 0L; }
}
