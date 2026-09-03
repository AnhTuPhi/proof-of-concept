package com.demo.resilience.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding window log: record each admitted timestamp; admit if the count
 * inside the trailing window is below the limit.
 *
 * Pro: precise — no boundary-edge bursting like fixed windows.
 * Con: O(N) memory per key for N requests in window. Fine here, ugly at scale —
 *      production usually swaps in a sliding-window-counter approximation.
 */
public final class SlidingWindowLog {

    private final int limit;
    private final long windowNanos;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public SlidingWindowLog(int limit, Duration window) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
        this.windowNanos = window.toNanos();
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        evictOlderThan(now - windowNanos);
        if (timestamps.size() < limit) {
            timestamps.addLast(now);
            return true;
        }
        return false;
    }

    public synchronized int currentCount() {
        evictOlderThan(System.nanoTime() - windowNanos);
        return timestamps.size();
    }

    private void evictOlderThan(long cutoffNanos) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoffNanos) {
            timestamps.pollFirst();
        }
    }
}
