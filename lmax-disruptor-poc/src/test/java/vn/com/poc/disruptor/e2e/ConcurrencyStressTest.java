package vn.com.poc.disruptor.e2e;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.ingest.ExchangeFeedSimulator;
import vn.com.poc.disruptor.ingest.FeedConfig;
import vn.com.poc.disruptor.metrics.ReconciliationReport;
import vn.com.poc.disruptor.outbox.OutboxStore;
import vn.com.poc.disruptor.pipeline.DisruptorPipeline;
import vn.com.poc.disruptor.pipeline.PipelineConfig;
import vn.com.poc.disruptor.pipeline.SymbolState;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No fault injection here on purpose — the point is to hammer the ring
 * buffer with many concurrent producer threads and many parallel
 * symbol-sharded business handlers, repeatedly, and prove the counts come
 * out <b>exactly</b> right every single time. Any race in the sharding logic,
 * the sequence barrier wiring, or the shared {@code ConcurrentHashMap} of
 * {@link SymbolState} would show up here as a flaky count — ten repetitions
 * with a fresh ring buffer each time gives ten independent chances to catch
 * that flakiness instead of one.
 */
class ConcurrencyStressTest {

    private static final List<String> SYMBOLS = List.of(
            "VND", "FPT", "HPG", "VIC", "MWG", "VHM", "MBB", "GAS", "CTG", "BID");

    @RepeatedTest(10)
    void highConcurrencyProducesExactCounts(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal-" + System.nanoTime() + ".log");
        Path quarantine = dir.resolve("quarantine-" + System.nanoTime() + ".log");
        String jdbcUrl = "jdbc:h2:" + dir.resolve("outbox-" + System.nanoTime()).toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE";

        try (OutboxStore outboxStore = new OutboxStore(jdbcUrl)) {
            PipelineConfig config = new PipelineConfig(
                    1 << 15, 16, 8, new YieldingWaitStrategy(), journal, quarantine);
            DisruptorPipeline pipeline = new DisruptorPipeline(config, outboxStore);
            RingBuffer<MarketEvent> ringBuffer = pipeline.ringBuffer();

            FeedConfig feedConfig = FeedConfig.happyPath(12, 10_000, SYMBOLS);
            long produced = ExchangeFeedSimulator.run(ringBuffer, feedConfig);
            pipeline.shutdown(30);

            assertEquals(feedConfig.totalAttempted(), produced, "no fault injection => every attempted publish must land");

            ReconciliationReport report = new ReconciliationReport(produced, pipeline.metrics().snapshot());
            assertTrue(report.isClean(), report.render());

            long sumAcrossShards = pipeline.symbolStates().values().stream()
                    .mapToLong(SymbolState::tradeCount)
                    .sum();
            assertEquals(produced, sumAcrossShards,
                    "sum of per-symbol trade counts must equal total processed events — "
                            + "a mismatch would mean a symbol was double-counted or dropped by the sharding logic");
        }
    }
}
