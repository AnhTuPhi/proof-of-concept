package vn.com.poc.disruptor.bench;

import com.lmax.disruptor.RingBuffer;
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
import vn.com.poc.disruptor.pipeline.WaitStrategies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point.
 *
 * <pre>
 *   mvnw -q compile exec:java  (or run the shaded jar)
 *   java -jar target/lmax-disruptor-poc.jar mode=demo
 *   java -jar target/lmax-disruptor-poc.jar mode=benchmark sessions=8 eventsPerSession=1000000 waitStrategy=yielding
 *   java -jar target/lmax-disruptor-poc.jar mode=chaos downstreamFailureRate=0.3 dropRate=0.001 corruptRate=0.001
 * </pre>
 */
public final class BenchmarkRunner {

    private static final List<String> SYMBOLS = List.of(
            "VND", "FPT", "HPG", "VIC", "MWG", "VHM", "MBB", "GAS",
            "CTG", "BID", "VCB", "ACB", "SSI", "VNM", "MSN", "POW",
            "PLX", "STB", "TCB", "HDB");

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String mode = opt.getOrDefault("mode", "benchmark");

        int sessions = Integer.parseInt(opt.getOrDefault("sessions", "8"));
        long eventsPerSession = Long.parseLong(opt.getOrDefault("eventsPerSession",
                mode.equals("demo") ? "5000" : "1000000"));
        int ringBufferSize = Integer.parseInt(opt.getOrDefault("ringBufferSize", "1048576"));
        int businessWorkers = Integer.parseInt(opt.getOrDefault("businessWorkers",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        String waitStrategyName = opt.getOrDefault("waitStrategy", "yielding");
        int outboxWorkers = Integer.parseInt(opt.getOrDefault("outboxWorkers", "2"));
        int outboxBatchSize = Integer.parseInt(opt.getOrDefault("outboxBatchSize", "500"));

        double dropRate = Double.parseDouble(opt.getOrDefault("dropRate", mode.equals("chaos") ? "0.0005" : "0.0"));
        double duplicateRate = Double.parseDouble(opt.getOrDefault("duplicateRate", mode.equals("chaos") ? "0.0005" : "0.0"));
        double corruptRate = Double.parseDouble(opt.getOrDefault("corruptRate", mode.equals("chaos") ? "0.0005" : "0.0"));
        double downstreamFailureRate = Double.parseDouble(opt.getOrDefault("downstreamFailureRate",
                mode.equals("chaos") ? "0.3" : "0.0"));

        Path workDir = Path.of("target", "run-" + System.currentTimeMillis());
        Files.createDirectories(workDir);
        Path journalPath = workDir.resolve("journal.log");
        Path quarantinePath = workDir.resolve("quarantine.log");
        String jdbcUrl = "jdbc:h2:" + workDir.resolve("outbox").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";

        System.out.println("=== lmax-disruptor-poc :: mode=" + mode + " ===");
        System.out.printf("sessions=%d eventsPerSession=%d totalAttempted=%d ringBufferSize=%d businessWorkers=%d waitStrategy=%s%n",
                sessions, eventsPerSession, (long) sessions * eventsPerSession, ringBufferSize, businessWorkers, waitStrategyName);
        System.out.printf("faults: dropRate=%.4f duplicateRate=%.4f corruptRate=%.4f downstreamFailureRate=%.2f%n",
                dropRate, duplicateRate, corruptRate, downstreamFailureRate);

        try (OutboxStore outboxStore = new OutboxStore(jdbcUrl)) {
            LatencyRecorder latencyRecorder = new LatencyRecorder();
            PipelineConfig pipelineConfig = new PipelineConfig(
                    ringBufferSize, Math.max(sessions, 1), businessWorkers,
                    WaitStrategies.byName(waitStrategyName), journalPath, quarantinePath);

            DisruptorPipeline pipeline = new DisruptorPipeline(pipelineConfig, outboxStore, latencyRecorder);
            RingBuffer<MarketEvent> ringBuffer = pipeline.ringBuffer();

            FeedConfig feedConfig = new FeedConfig(sessions, eventsPerSession, SYMBOLS,
                    dropRate, duplicateRate, corruptRate, 42L);

            Instant t0 = Instant.now();
            long produced = ExchangeFeedSimulator.run(ringBuffer, feedConfig);
            Instant t1 = Instant.now();
            pipeline.shutdown(60);
            Instant t2 = Instant.now();

            Duration ingestDuration = Duration.between(t0, t1);
            Duration drainDuration = Duration.between(t1, t2);
            Duration totalPipelineDuration = Duration.between(t0, t2);
            double throughputEventsPerSec = produced / Math.max(1e-9, totalPipelineDuration.toNanos() / 1e9);

            System.out.println();
            System.out.println("--- Ring-buffer-to-outbox stage ---");
            System.out.printf("produced=%d in %s (producer-side wall time)%n", produced, ingestDuration);
            System.out.printf("chain drain time (last consumer catching up)=%s%n", drainDuration);
            System.out.printf("end-to-end pipeline time=%s%n", totalPipelineDuration);
            System.out.printf("throughput = %.0f events/sec%n", throughputEventsPerSec);
            System.out.println(latencyRecorder.renderSummary());

            System.out.println();
            System.out.println(new ReconciliationReport(produced, pipeline.metrics().snapshot()).render());

            System.out.println("--- Outbox dispatch (retry/backoff) ---");
            FlakyDownstreamPublisher publisher = new FlakyDownstreamPublisher(downstreamFailureRate);
            RetryBackoffPolicy backoff = RetryBackoffPolicy.defaultPolicy();
            OutboxDispatcher dispatcher = new OutboxDispatcher(outboxStore, publisher, backoff,
                    pipeline.metrics(), outboxWorkers, outboxBatchSize, Duration.ofMillis(20));
            Instant d0 = Instant.now();
            dispatcher.start();
            boolean drained = dispatcher.awaitDrain(Duration.ofMinutes(5));
            Instant d1 = Instant.now();
            dispatcher.close();

            System.out.printf("outbox drained=%s in %s (publisher callCount=%d, published=%d)%n",
                    drained, Duration.between(d0, d1), publisher.callCount(), publisher.publishedCount());

            ReconciliationReport finalReport = new ReconciliationReport(produced, pipeline.metrics().snapshot());
            if (drained) {
                finalReport.checkOutboxDrained();
            }
            System.out.println();
            System.out.println(finalReport.render());
            System.out.printf("dead-letter rows=%d%n", outboxStore.countByStatus("DEAD_LETTER"));

            if (!finalReport.isClean()) {
                System.exit(1);
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0) {
                map.put(arg.substring(0, eq), arg.substring(eq + 1));
            }
        }
        return map;
    }
}
