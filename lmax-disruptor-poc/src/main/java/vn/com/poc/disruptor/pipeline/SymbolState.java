package vn.com.poc.disruptor.pipeline;

import vn.com.poc.disruptor.event.MarketEvent;

/**
 * Running per-symbol state (trade count, volume, a running VWAP).
 *
 * <p>Deliberately holds plain, non-atomic fields. That is safe only because
 * of the sharding invariant enforced by {@link BusinessLogicHandler}: exactly
 * one worker thread owns a given symbol for the lifetime of the run, so a
 * given {@code SymbolState} instance is only ever mutated by one thread.
 * The surrounding {@code ConcurrentHashMap} in {@code BusinessLogicHandler}
 * only needs to be concurrency-safe for its own bucket/resize bookkeeping —
 * not for this object's fields.
 */
public final class SymbolState {

    private long tradeCount;
    private long totalQuantity;
    private double vwapNumerator; // sum(price * qty)
    private double lastPrice;

    public void apply(MarketEvent event) {
        tradeCount++;
        totalQuantity += event.quantity();
        vwapNumerator += event.price() * event.quantity();
        lastPrice = event.price();
    }

    public long tradeCount() {
        return tradeCount;
    }

    public long totalQuantity() {
        return totalQuantity;
    }

    public double vwap() {
        return totalQuantity == 0 ? 0.0 : vwapNumerator / totalQuantity;
    }

    public double lastPrice() {
        return lastPrice;
    }
}
