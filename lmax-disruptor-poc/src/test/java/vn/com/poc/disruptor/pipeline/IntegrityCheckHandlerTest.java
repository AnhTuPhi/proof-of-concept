package vn.com.poc.disruptor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.com.poc.disruptor.event.EventType;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.metrics.PipelineMetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrityCheckHandlerTest {

    private PipelineMetrics metrics;
    private IntegrityCheckHandler handler;

    @BeforeEach
    void setUp() {
        metrics = new PipelineMetrics();
        handler = new IntegrityCheckHandler(4, metrics);
    }

    private MarketEvent event(long exchangeSeq, int sessionId) {
        MarketEvent e = new MarketEvent();
        e.set(exchangeSeq, sessionId, "VND", EventType.TRADE, 20.5, 100, 'B', System.nanoTime());
        return e;
    }

    @Test
    void acceptsInOrderEvents() throws Exception {
        for (long seq = 0; seq < 5; seq++) {
            handler.onEvent(event(seq, 0), seq, false);
        }
        PipelineMetrics.Snapshot s = metrics.snapshot();
        assertEquals(5, s.received());
        assertEquals(5, s.integrityPassed());
        assertEquals(0, s.integrityFailed());
        assertEquals(0, s.gapsDetected());
        assertEquals(0, s.duplicatesDetected());
    }

    @Test
    void detectsCorruptionViaChecksumMismatch() throws Exception {
        MarketEvent corrupted = event(0, 0);
        corrupted.corruptQuantityForTest();

        handler.onEvent(corrupted, 0, false);

        assertTrue(corrupted.isPoisoned());
        assertEquals(1, metrics.snapshot().integrityFailed());
        assertEquals(0, metrics.snapshot().integrityPassed());
    }

    @Test
    void detectsGapWhenSequenceJumpsForward() throws Exception {
        handler.onEvent(event(0, 0), 0, false);
        MarketEvent afterGap = event(5, 0); // seq 1..4 never arrived
        handler.onEvent(afterGap, 1, false);

        assertEquals(4, afterGap.gapDetected());
        assertEquals(4, metrics.snapshot().gapsDetected());
    }

    @Test
    void detectsDuplicateWhenSequenceRepeats() throws Exception {
        handler.onEvent(event(3, 0), 0, false);
        MarketEvent redelivered = event(3, 0);
        handler.onEvent(redelivered, 1, false);

        assertTrue(redelivered.isDuplicate());
        assertEquals(1, metrics.snapshot().duplicatesDetected());
        // duplicate does not count as a gap
        assertEquals(0, metrics.snapshot().gapsDetected());
    }

    @Test
    void tracksSessionsIndependently() throws Exception {
        handler.onEvent(event(0, 0), 0, false);
        handler.onEvent(event(0, 1), 1, false); // seq 0 is valid first-event for session 1 too

        PipelineMetrics.Snapshot s = metrics.snapshot();
        assertEquals(0, s.gapsDetected());
        assertEquals(0, s.duplicatesDetected());
        assertFalse(false); // no exception thrown => sessions did not interfere
    }
}
