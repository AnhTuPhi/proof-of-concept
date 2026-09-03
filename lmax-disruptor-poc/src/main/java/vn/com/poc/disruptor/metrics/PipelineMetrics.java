package vn.com.poc.disruptor.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Every counter here is a {@link LongAdder}, not a plain {@code long} or even
 * an {@code AtomicLong}: multiple business-logic shards increment
 * {@code businessProcessed} concurrently on every batch, and LongAdder is
 * built exactly for "many threads, mostly writes, occasional read" — it
 * stripes the counter across cache lines internally so increments don't
 * contend, at the cost of a slightly more expensive {@code sum()} read. That
 * read only happens when we print the reconciliation report, i.e. rarely.
 */
public final class PipelineMetrics {

    private final LongAdder received = new LongAdder();
    private final LongAdder integrityPassed = new LongAdder();
    private final LongAdder integrityFailed = new LongAdder();
    private final LongAdder gapsDetected = new LongAdder();
    private final LongAdder duplicatesDetected = new LongAdder();
    private final LongAdder journaled = new LongAdder();
    private final LongAdder quarantined = new LongAdder();
    private final LongAdder businessProcessed = new LongAdder();
    private final LongAdder outboxCreated = new LongAdder();
    private final LongAdder outboxDispatched = new LongAdder();
    private final LongAdder outboxDeadLettered = new LongAdder();
    private final LongAdder outboxRetries = new LongAdder();

    public void incReceived() {
        received.increment();
    }

    public void incIntegrityPassed() {
        integrityPassed.increment();
    }

    public void incIntegrityFailed() {
        integrityFailed.increment();
    }

    public void addGapsDetected(long n) {
        if (n > 0) {
            gapsDetected.add(n);
        }
    }

    public void incDuplicatesDetected() {
        duplicatesDetected.increment();
    }

    public void incJournaled() {
        journaled.increment();
    }

    public void incQuarantined() {
        quarantined.increment();
    }

    public void incBusinessProcessed() {
        businessProcessed.increment();
    }

    public void incOutboxCreated() {
        outboxCreated.increment();
    }

    public void incOutboxDispatched() {
        outboxDispatched.increment();
    }

    public void incOutboxDeadLettered() {
        outboxDeadLettered.increment();
    }

    public void incOutboxRetries() {
        outboxRetries.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                received.sum(),
                integrityPassed.sum(),
                integrityFailed.sum(),
                gapsDetected.sum(),
                duplicatesDetected.sum(),
                journaled.sum(),
                quarantined.sum(),
                businessProcessed.sum(),
                outboxCreated.sum(),
                outboxDispatched.sum(),
                outboxDeadLettered.sum(),
                outboxRetries.sum());
    }

    public record Snapshot(
            long received,
            long integrityPassed,
            long integrityFailed,
            long gapsDetected,
            long duplicatesDetected,
            long journaled,
            long quarantined,
            long businessProcessed,
            long outboxCreated,
            long outboxDispatched,
            long outboxDeadLettered,
            long outboxRetries) {
    }
}
