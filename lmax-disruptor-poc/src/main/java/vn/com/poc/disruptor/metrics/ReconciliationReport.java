package vn.com.poc.disruptor.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw counters into the actual "did we receive everything, did we
 * process everything" proof.
 *
 * <p>The invariants below are the point of the whole exercise — they are what
 * "nhan du, xu ly du" (received in full, processed in full) means in
 * measurable terms, instead of a vibe:
 *
 * <ol>
 *   <li>{@code received == producedTotal} — nothing the producer published was
 *       ever dropped between the ring buffer and the integrity stage. This
 *       one is structural: it is what the Disruptor's ring buffer guarantees
 *       by construction (bounded, never-overwrite-unread-slot), so a failure
 *       here means a bug in this POC, not a fluke.</li>
 *   <li>{@code integrityPassed + integrityFailed == received} — every event is
 *       classified, none silently skipped by the checksum stage.</li>
 *   <li>{@code journaled == received - integrityFailed} — every
 *       non-corrupted event is durably logged (write-ahead) before business
 *       logic touches it, so a crash after this point is replayable.</li>
 *   <li>{@code businessProcessed == journaled - duplicatesDetected} —
 *       duplicates are journaled (for audit) but not double-applied to
 *       position/order-book state.</li>
 *   <li>{@code outboxCreated == businessProcessed} — every state change gets
 *       exactly one outbox row queued for downstream publication.</li>
 *   <li>{@code outboxDispatched + outboxDeadLettered == outboxCreated} — once
 *       the dispatcher has drained, every outbox row has reached a terminal
 *       state: either it left the building, or it is sitting in the
 *       dead-letter bucket where someone can see it. Nothing vanishes
 *       silently.</li>
 * </ol>
 */
public final class ReconciliationReport {

    private final long producedTotal;
    private final PipelineMetrics.Snapshot s;
    private final List<String> violations = new ArrayList<>();

    public ReconciliationReport(long producedTotal, PipelineMetrics.Snapshot snapshot) {
        this.producedTotal = producedTotal;
        this.s = snapshot;
        check("received == produced", s.received(), producedTotal);
        check("integrityPassed + integrityFailed == received",
                s.integrityPassed() + s.integrityFailed(), s.received());
        check("journaled == received - integrityFailed",
                s.journaled(), s.received() - s.integrityFailed());
        check("businessProcessed == journaled - duplicatesDetected",
                s.businessProcessed(), s.journaled() - s.duplicatesDetected());
        check("outboxCreated == businessProcessed", s.outboxCreated(), s.businessProcessed());
    }

    /**
     * Outbox dispatch is asynchronous (retry/backoff takes real wall-clock
     * time), so this invariant is only meaningful once the dispatcher has
     * fully drained — call it after waiting for that, not as part of the
     * constructor.
     */
    public void checkOutboxDrained() {
        check("outboxDispatched + outboxDeadLettered == outboxCreated",
                s.outboxDispatched() + s.outboxDeadLettered(), s.outboxCreated());
    }

    private void check(String rule, long actual, long expected) {
        if (actual != expected) {
            violations.add(rule + "  =>  actual=" + actual + " expected=" + expected);
        }
    }

    public boolean isClean() {
        return violations.isEmpty();
    }

    public List<String> violations() {
        return violations;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Reconciliation report ===\n");
        sb.append(String.format("%-28s %d%n", "produced", producedTotal));
        sb.append(String.format("%-28s %d%n", "received", s.received()));
        sb.append(String.format("%-28s %d%n", "integrityPassed", s.integrityPassed()));
        sb.append(String.format("%-28s %d%n", "integrityFailed(corrupt)", s.integrityFailed()));
        sb.append(String.format("%-28s %d%n", "gapsDetected(missing seq)", s.gapsDetected()));
        sb.append(String.format("%-28s %d%n", "duplicatesDetected", s.duplicatesDetected()));
        sb.append(String.format("%-28s %d%n", "journaled", s.journaled()));
        sb.append(String.format("%-28s %d%n", "quarantined(poisoned)", s.quarantined()));
        sb.append(String.format("%-28s %d%n", "businessProcessed", s.businessProcessed()));
        sb.append(String.format("%-28s %d%n", "outboxCreated", s.outboxCreated()));
        sb.append(String.format("%-28s %d%n", "outboxDispatched", s.outboxDispatched()));
        sb.append(String.format("%-28s %d%n", "outboxDeadLettered", s.outboxDeadLettered()));
        sb.append(String.format("%-28s %d%n", "outboxRetries", s.outboxRetries()));
        if (isClean()) {
            sb.append("RESULT: OK -- all invariants hold (received du, xu ly du)\n");
        } else {
            sb.append("RESULT: VIOLATIONS:\n");
            for (String v : violations) {
                sb.append("  - ").append(v).append('\n');
            }
        }
        return sb.toString();
    }
}
