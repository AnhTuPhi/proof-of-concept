package vn.com.ipas.poc.oversell;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-threaded actor for a hot SKU. Every buy is enqueued, the worker
 * processes one at a time. Trades concurrency for predictability — useful
 * when business rules around a SKU are too rich for a single CAS (e.g.,
 * combo limits, per-user quotas, fraud checks).
 *
 * In a real system this is one consumer per SKU on a partitioned Kafka topic
 * (sku-id used as partition key so the same SKU always lands on the same partition).
 */
public final class QueueSerializedStrategy implements InventoryStrategy, AutoCloseable {
    private int stock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sku-worker");
        t.setDaemon(true);
        return t;
    });

    @Override public String name() { return "Queue-serialised (single worker per SKU)"; }
    @Override public void seed(int initial) {
        try {
            worker.submit(() -> { stock = initial; }).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @Override public int currentStock() {
        try {
            return CompletableFuture.supplyAsync(() -> stock, worker).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean tryBuy(int qty) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (stock >= qty) {
                    stock -= qty;
                    return true;
                }
                return false;
            }, worker).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() { worker.shutdownNow(); }
}
