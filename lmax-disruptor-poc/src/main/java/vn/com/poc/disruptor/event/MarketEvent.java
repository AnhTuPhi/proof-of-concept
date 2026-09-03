package vn.com.poc.disruptor.event;

import vn.com.poc.disruptor.util.Checksums;

/**
 * Mutable slot in the Disruptor ring buffer.
 *
 * <p>The Disruptor pre-allocates {@code ringBufferSize} instances of this
 * class once, at startup, and reuses them forever — publishing an event means
 * <i>mutating a slot in place</i>, not allocating a new object. That is the
 * whole "mechanical sympathy" pitch: zero garbage on the hot path, so GC
 * pauses don't show up as latency spikes under sustained load.
 *
 * <p>Every field here is only ever written by exactly one thread at a time
 * for a given sequence: the producer thread while publishing, then each
 * pipeline stage in turn while the SequenceBarrier holds later stages back.
 * The Disruptor's happens-before edges (a volatile store to the cursor
 * sequence on publish, a volatile load of it on the consumer side) are what
 * make that handoff safe without any lock or {@code synchronized} block —
 * see TECHNICAL.md section 1.
 */
public final class MarketEvent {

    private long exchangeSeq;
    private int sessionId;
    private String symbol;
    private EventType type;
    private long priceBits; // Double.doubleToLongBits(price) — deterministic for hashing
    private long quantity;
    private char side; // 'B' or 'S'
    private long ingestNanos;
    private long expectedChecksum;

    // Set by IntegrityCheckHandler; read by every stage after it.
    private boolean poisoned;
    private boolean duplicate;
    private long gapDetected; // number of missing exchangeSeq values observed before this one, 0 if none

    public void set(long exchangeSeq, int sessionId, String symbol, EventType type,
                     double price, long quantity, char side, long ingestNanos) {
        this.exchangeSeq = exchangeSeq;
        this.sessionId = sessionId;
        this.symbol = symbol;
        this.type = type;
        this.priceBits = Double.doubleToLongBits(price);
        this.quantity = quantity;
        this.side = side;
        this.ingestNanos = ingestNanos;
        this.expectedChecksum = computeChecksum();
        this.poisoned = false;
        this.duplicate = false;
        this.gapDetected = 0;
    }

    /** Recomputes the checksum from the current field values (used by the integrity stage). */
    public long computeChecksum() {
        return Checksums.fnv1a64(exchangeSeq, sessionId, symbol, type.ordinal(), priceBits, quantity, side);
    }

    /** Test/chaos-injection hook only: mutate a field after publish to simulate corruption in flight. */
    public void corruptQuantityForTest() {
        this.quantity = this.quantity ^ 0xFF;
    }

    public long exchangeSeq() {
        return exchangeSeq;
    }

    public int sessionId() {
        return sessionId;
    }

    public String symbol() {
        return symbol;
    }

    public EventType type() {
        return type;
    }

    public double price() {
        return Double.longBitsToDouble(priceBits);
    }

    public long quantity() {
        return quantity;
    }

    public char side() {
        return side;
    }

    public long ingestNanos() {
        return ingestNanos;
    }

    public long expectedChecksum() {
        return expectedChecksum;
    }

    public boolean isPoisoned() {
        return poisoned;
    }

    public void markPoisoned() {
        this.poisoned = true;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void markDuplicate() {
        this.duplicate = true;
    }

    public long gapDetected() {
        return gapDetected;
    }

    public void setGapDetected(long gap) {
        this.gapDetected = gap;
    }

    /** Simple, human-diffable payload for the journal and the outbox. */
    public String toWireLine() {
        return exchangeSeq + "|" + sessionId + "|" + symbol + "|" + type + "|"
                + price() + "|" + quantity + "|" + side + "|" + ingestNanos;
    }

    public String toJsonPayload() {
        return "{"
                + "\"exchangeSeq\":" + exchangeSeq + ","
                + "\"sessionId\":" + sessionId + ","
                + "\"symbol\":\"" + symbol + "\","
                + "\"type\":\"" + type + "\","
                + "\"price\":" + price() + ","
                + "\"quantity\":" + quantity + ","
                + "\"side\":\"" + side + "\""
                + "}";
    }
}
