package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.EventHandler;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.metrics.PipelineMetrics;

/**
 * First stage of the chain. Single instance, runs sequentially over every
 * event the ring buffer ever holds — that ordering guarantee (the Disruptor
 * hands sequences to a single {@link EventHandler} strictly in order, one at
 * a time) is exactly what makes the per-session gap/duplicate detection below
 * correct without any locking: this thread is the only thing that ever reads
 * or writes {@link #expectedNextSeq}.
 *
 * <p>Two independent checks, both feeding {@link PipelineMetrics}:
 * <ul>
 *   <li><b>Integrity</b> — recompute the checksum and compare to the one the
 *       producer stamped at publish time. A mismatch means the event was
 *       corrupted somewhere between production and here; it is marked
 *       {@code poisoned} and every later stage skips it.</li>
 *   <li><b>Completeness</b> — per exchange session, track the next expected
 *       {@code exchangeSeq}. A seq higher than expected means the feed lost
 *       messages in between (a gap); a seq lower than the last one we advanced
 *       past means this is a redelivered duplicate.</li>
 * </ul>
 */
public final class IntegrityCheckHandler implements EventHandler<MarketEvent> {

    private final long[] expectedNextSeq;
    private final PipelineMetrics metrics;

    public IntegrityCheckHandler(int maxSessions, PipelineMetrics metrics) {
        this.expectedNextSeq = new long[maxSessions];
        java.util.Arrays.fill(this.expectedNextSeq, -1L);
        this.metrics = metrics;
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) {
        metrics.incReceived();

        if (event.computeChecksum() != event.expectedChecksum()) {
            event.markPoisoned();
            metrics.incIntegrityFailed();
        } else {
            metrics.incIntegrityPassed();
        }

        trackSequence(event);
    }

    private void trackSequence(MarketEvent event) {
        int sessionId = event.sessionId();
        long seq = event.exchangeSeq();
        long expected = expectedNextSeq[sessionId];

        if (expected < 0) {
            expectedNextSeq[sessionId] = seq + 1;
        } else if (seq < expected) {
            event.markDuplicate();
            // Only count it toward the reconciliation metric if it also passed the
            // checksum: a poisoned+duplicate event is already excluded from
            // journaled/businessProcessed by its poisoned flag alone, so counting
            // it here too would make journaled - duplicatesDetected undercount.
            if (!event.isPoisoned()) {
                metrics.incDuplicatesDetected();
            }
        } else if (seq == expected) {
            expectedNextSeq[sessionId] = seq + 1;
        } else {
            long gap = seq - expected;
            event.setGapDetected(gap);
            metrics.addGapsDetected(gap);
            expectedNextSeq[sessionId] = seq + 1;
        }
    }
}
