package vn.com.poc.disruptor.ingest;

import java.util.List;

/**
 * Load-shape for {@link ExchangeFeedSimulator}.
 *
 * @param sessionCount    number of independent exchange gateway connections
 *                        (each is one producer thread with its own monotonic
 *                        {@code exchangeSeq})
 * @param eventsPerSession events each session attempts to send
 * @param symbols         symbol universe drawn from round-robin per session
 * @param dropRate        probability [0,1) a given exchangeSeq is never
 *                        published at all (simulates a lost packet — this is
 *                        what the gap detector in IntegrityCheckHandler exists
 *                        to catch)
 * @param duplicateRate   probability [0,1) the previous event is re-published
 *                        with the same exchangeSeq right after a normal publish
 *                        (simulates at-least-once redelivery from the feed)
 * @param corruptRate     probability [0,1) a published event has a field
 *                        flipped after the checksum was computed, simulating
 *                        corruption in flight (this is what the checksum
 *                        recheck in IntegrityCheckHandler exists to catch)
 * @param seed            RNG seed, one per session (seed + sessionId) — same
 *                        seed reproduces the same fault pattern, which is what
 *                        makes the reconciliation assertions in tests exact
 *                        rather than probabilistic
 */
public record FeedConfig(
        int sessionCount,
        long eventsPerSession,
        List<String> symbols,
        double dropRate,
        double duplicateRate,
        double corruptRate,
        long seed) {

    public static FeedConfig happyPath(int sessionCount, long eventsPerSession, List<String> symbols) {
        return new FeedConfig(sessionCount, eventsPerSession, symbols, 0.0, 0.0, 0.0, 42L);
    }

    public long totalAttempted() {
        return (long) sessionCount * eventsPerSession;
    }
}
