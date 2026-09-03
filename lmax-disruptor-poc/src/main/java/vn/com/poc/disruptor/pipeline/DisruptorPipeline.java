package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import vn.com.poc.disruptor.bench.LatencyRecorder;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.event.MarketEventFactory;
import vn.com.poc.disruptor.metrics.PipelineMetrics;
import vn.com.poc.disruptor.outbox.OutboxStore;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the ring buffer and the four-stage handler chain together:
 *
 * <pre>
 *   ExchangeFeedSimulator (N producer threads, MULTI producer sequencer)
 *          |
 *          v
 *   RingBuffer&lt;MarketEvent&gt;  (pre-allocated slots, lock-free CAS handoff)
 *          |
 *          v
 *   IntegrityCheckHandler   (1 instance)   -- checksum + gap/duplicate detection
 *          |
 *          v
 *   JournalHandler          (1 instance)   -- durable write-ahead log (fsync per batch)
 *          |
 *          v
 *   BusinessLogicHandler[]  (N instances)  -- symbol-sharded, lock-free by construction
 *          |  (barrier waits for the slowest of the N)
 *          v
 *   OutboxHandler           (1 instance)   -- batched JDBC insert into the outbox table
 * </pre>
 *
 * Everything past the ring buffer runs on the Disruptor's own consumer
 * threads. Outbox <b>dispatch</b> (the retry/backoff loop talking to the
 * flaky downstream) deliberately does not: see {@code OutboxDispatcher},
 * which runs on its own thread(s) so a slow/failing downstream can never back
 * up into the ring buffer.
 */
public final class DisruptorPipeline implements AutoCloseable {

    private final Disruptor<MarketEvent> disruptor;
    private final RingBuffer<MarketEvent> ringBuffer;
    private final JournalHandler journalHandler;
    private final OutboxHandler outboxHandler;
    private final Map<String, SymbolState> symbolStates;
    private final PipelineMetrics metrics = new PipelineMetrics();

    public DisruptorPipeline(PipelineConfig cfg, OutboxStore outboxStore) throws IOException, SQLException {
        this(cfg, outboxStore, null);
    }

    public DisruptorPipeline(PipelineConfig cfg, OutboxStore outboxStore, LatencyRecorder latencyRecorder)
            throws IOException, SQLException {
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory threadFactory = r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("disruptor-consumer-" + threadCounter.getAndIncrement());
            return t;
        };

        this.disruptor = new Disruptor<>(new MarketEventFactory(), cfg.ringBufferSize(), threadFactory,
                ProducerType.MULTI, cfg.waitStrategy());

        IntegrityCheckHandler integrityHandler = new IntegrityCheckHandler(cfg.maxSessions(), metrics);
        this.journalHandler = new JournalHandler(cfg.journalPath(), cfg.quarantinePath(), metrics);
        this.symbolStates = BusinessLogicHandler.newSharedStateMap();

        int workerCount = Math.max(1, cfg.businessWorkerCount());
        BusinessLogicHandler[] businessHandlers = new BusinessLogicHandler[workerCount];
        for (int i = 0; i < workerCount; i++) {
            businessHandlers[i] = new BusinessLogicHandler(i, workerCount, symbolStates, metrics);
        }
        this.outboxHandler = new OutboxHandler(outboxStore, metrics, latencyRecorder);

        disruptor.handleEventsWith(integrityHandler)
                .then(journalHandler)
                .then(businessHandlers)
                .then(outboxHandler);

        this.ringBuffer = disruptor.start();
    }

    public RingBuffer<MarketEvent> ringBuffer() {
        return ringBuffer;
    }

    public PipelineMetrics metrics() {
        return metrics;
    }

    public Map<String, SymbolState> symbolStates() {
        return symbolStates;
    }

    /** Blocks until every published sequence has cleared the last handler in the chain. */
    public void shutdown(long timeoutSeconds) throws IOException, SQLException {
        disruptor.shutdown(); // waits for the ring buffer to drain, no timeout overload needed for a bounded benchmark run
        journalHandler.close();
        outboxHandler.close();
    }

    @Override
    public void close() throws Exception {
        shutdown(30);
    }
}
