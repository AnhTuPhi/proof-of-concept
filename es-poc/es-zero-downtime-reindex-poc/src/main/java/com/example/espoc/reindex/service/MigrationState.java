package com.example.espoc.reindex.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for migration phase. Read by {@link ProductWriter} to decide whether to
 * dual-write, by the controller to report status, and updated by {@link MigrationService}.
 */
@Component
public class MigrationState {

    public enum Phase {
        IDLE,                       // alias → v1 only
        DUAL_WRITE_ENABLED,         // alias → v1 still; new writes go to both
        REINDEXING,                 // reindex task is running
        READY_TO_SWAP,              // reindex done, awaiting swap
        SWAPPED,                    // alias → v2; dual-write still on briefly
        COMPLETED,                  // dual-write off, v1 may still exist for rollback
        ROLLED_BACK
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.IDLE);
    private volatile Instant phaseEnteredAt = Instant.now();
    private volatile String reindexTaskId;
    private volatile long lastReindexCreated;
    private volatile long lastReindexUpdated;
    private volatile String lastError;

    public Phase phase() { return phase.get(); }
    public Instant phaseEnteredAt() { return phaseEnteredAt; }
    public String reindexTaskId() { return reindexTaskId; }
    public long lastReindexCreated() { return lastReindexCreated; }
    public long lastReindexUpdated() { return lastReindexUpdated; }
    public String lastError() { return lastError; }

    public boolean isDualWriteActive() {
        Phase p = phase.get();
        return p == Phase.DUAL_WRITE_ENABLED || p == Phase.REINDEXING ||
               p == Phase.READY_TO_SWAP || p == Phase.SWAPPED;
    }

    public void setPhase(Phase p) {
        phase.set(p);
        phaseEnteredAt = Instant.now();
    }
    public void setReindexTaskId(String id) { this.reindexTaskId = id; }
    public void setReindexProgress(long created, long updated) {
        this.lastReindexCreated = created;
        this.lastReindexUpdated = updated;
    }
    public void setLastError(String err) { this.lastError = err; }
}
