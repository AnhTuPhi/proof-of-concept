package vn.com.poc.disruptor.e2e;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.ingest.ExchangeFeedSimulator;
import vn.com.poc.disruptor.ingest.FeedConfig;
import vn.com.poc.disruptor.metrics.ReconciliationReport;
import vn.com.poc.disruptor.outbox.FlakyDownstreamPublisher;
import vn.com.poc.disruptor.outbox.OutboxDispatcher;
import vn.com.poc.disruptor.outbox.OutboxStore;
import vn.com.poc.disruptor.outbox.RetryBackoffPolicy;
import vn.com.poc.disruptor.pipeline.DisruptorPipeline;
import vn.com.poc.disruptor.pipeline.PipelineConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of "nhan du, xu ly du" (received in full, processed in
 * full): runs a moderate load with every fault type turned on at once
 * (dropped packets, redelivered duplicates, corrupted payloads, a flaky
 * downstream) and asserts every {@link ReconciliationReport} invariant still
 * holds exactly — not approximately.
 */
class PipelineReconciliationTest {

    private static final List<String> SYMBOLS = List.of("VND", "FPT", "HPG", "VIC", "MWG");

    @Test
    void allInvariantsHoldUnderCombinedFaultInjection(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal.log");
        Path quarantine = dir.resolve("quarantine.log");
        String jdbcUrl = "jdbc:h2:" + dir.resolve("outbox").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";

        try (OutboxStore outboxStore = new OutboxStore(jdbcUrl)) {
            PipelineConfig config = new PipelineConfig(
                    1 << 14, /* ringBufferSize */
                    8,       /* maxSessions */
                    4,       /* businessWorkers */
                    new BlockingWaitStrategy(),
                    journal, quarantine);

            DisruptorPipeline pipeline = new DisruptorPipeline(config, outboxStore);
            RingBuffer<MarketEvent> ringBuffer = pipeline.ringBuffer();

            FeedConfig feedConfig = new FeedConfig(
                    6,     /* sessions */
                    5_000, /* eventsPerSession */
                    SYMBOLS,
                    0.01,  /* dropRate */
                    0.01,  /* duplicateRate */
                    0.01,  /* corruptRate */
                    123L);

            long produced = ExchangeFeedSimulator.run(ringBuffer, feedConfig);
            pipeline.shutdown(30);

            ReconciliationReport ingestReport = new ReconciliationReport(produced, pipeline.metrics().snapshot());
            assertTrue(ingestReport.isClean(), ingestReport.render());

            // Sanity: with these rates over 30k attempted events, faults should
            // actually have fired — otherwise this test would pass vacuously.
            var s = pipeline.metrics().snapshot();
            assertTrue(s.integrityFailed() > 0, "expected some corrupted events to be detected");
            assertTrue(s.gapsDetected() > 0, "expected some dropped-packet gaps to be detected");
            assertTrue(s.duplicatesDetected() > 0, "expected some redelivered duplicates to be detected");

            // Row/retry volume here is intentionally modest: this embedded H2 file
            // is a lightweight stand-in for "some relational outbox table", not
            // itself under test at scale — see docs/PERFORMANCE.md for the
            // large-volume throughput numbers, measured with a healthy downstream
            // (no retry churn) precisely to keep the outbox store out of the way.
            FlakyDownstreamPublisher publisher = new FlakyDownstreamPublisher(0.25);
            OutboxDispatcher dispatcher = new OutboxDispatcher(outboxStore, publisher,
                    RetryBackoffPolicy.defaultPolicy(), pipeline.metrics(), 2, 100, Duration.ofMillis(20));
            dispatcher.start();
            try {
                assertTrue(dispatcher.awaitDrain(Duration.ofSeconds(60)), "outbox failed to drain in time");
            } finally {
                dispatcher.close();
            }

            ReconciliationReport finalReport = new ReconciliationReport(produced, pipeline.metrics().snapshot());
            finalReport.checkOutboxDrained();
            assertTrue(finalReport.isClean(), finalReport.render());
        }
    }
}
