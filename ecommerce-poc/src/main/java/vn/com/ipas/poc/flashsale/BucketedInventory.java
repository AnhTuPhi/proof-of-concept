package vn.com.ipas.poc.flashsale;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Split the hot SKU into N independent buckets so concurrent decrements
 * land on different CAS targets — kills the single-cache-line bottleneck.
 *
 * Pattern: pre-allocate N buckets per region/server. A buy picks a bucket
 * (usually by user-id hash, here random for simulation), tries CAS, and on
 * failure walks to the next bucket. Final stock-empty answer is "all N
 * buckets are empty" — checked cheaply because each bucket's empty state
 * is monotonic.
 */
public final class BucketedInventory {

    private final AtomicInteger[] buckets;
    private final LongAdder walks = new LongAdder();

    public BucketedInventory(int totalUnits, int bucketCount) {
        this.buckets = new AtomicInteger[bucketCount];
        int per = totalUnits / bucketCount;
        int remainder = totalUnits - per * bucketCount;
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new AtomicInteger(per + (i < remainder ? 1 : 0));
        }
    }

    public boolean tryBuy() {
        int start = ThreadLocalRandom.current().nextInt(buckets.length);
        for (int i = 0; i < buckets.length; i++) {
            int idx = (start + i) % buckets.length;
            AtomicInteger b = buckets[idx];
            int prev;
            do {
                prev = b.get();
                if (prev <= 0) break;
            } while (!b.compareAndSet(prev, prev - 1));
            if (prev > 0) {
                if (i > 0) walks.add(i);
                return true;
            }
        }
        return false;
    }

    public int totalRemaining() {
        int sum = 0;
        for (AtomicInteger b : buckets) sum += b.get();
        return sum;
    }

    public long bucketWalkCount() { return walks.sum(); }
    public int bucketCount() { return buckets.length; }
}
