package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.EventHandler;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.metrics.PipelineMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third stage, run as {@code workerCount} parallel instances
 * (see {@code DisruptorPipeline.handleEventsWith(a, b, c, ...)}).
 *
 * <p>Every instance sees <b>every</b> sequence — the Disruptor does not split
 * the ring buffer between them — but each one filters on
 * {@code hash(symbol) % workerCount == workerIndex} and no-ops otherwise.
 * That gives per-symbol ordering and exclusive ownership (all events for
 * "VND" are always handled by the same worker, in the order they were
 * published) while still spreading CPU-bound work for different symbols
 * across cores, with zero locking: no two workers ever touch the same
 * symbol's state.
 *
 * <p>Duplicates and poisoned events are skipped here so they are never
 * double-applied to position/order-book state or double-published — see
 * {@code ReconciliationReport} for the exact counting contract this depends
 * on.
 */
public final class BusinessLogicHandler implements EventHandler<MarketEvent> {

    private final int workerIndex;
    private final int workerCount;
    private final Map<String, SymbolState> symbolStates;
    private final PipelineMetrics metrics;

    public BusinessLogicHandler(int workerIndex, int workerCount,
                                 Map<String, SymbolState> sharedSymbolStates, PipelineMetrics metrics) {
        this.workerIndex = workerIndex;
        this.workerCount = workerCount;
        this.symbolStates = sharedSymbolStates;
        this.metrics = metrics;
    }

    public static Map<String, SymbolState> newSharedStateMap() {
        return new ConcurrentHashMap<>();
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) {
        if (event.isPoisoned() || event.isDuplicate()) {
            return;
        }
        int shard = Math.floorMod(event.symbol().hashCode(), workerCount);
        if (shard != workerIndex) {
            return;
        }
        symbolStates.computeIfAbsent(event.symbol(), k -> new SymbolState()).apply(event);
        metrics.incBusinessProcessed();
    }
}
