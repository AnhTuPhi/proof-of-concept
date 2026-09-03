package com.poc.kyc.model;

import java.time.Instant;
import java.util.UUID;

public class DlqEntry {
    private final String id;
    private final String caseId;
    private final KycState failedAtState;
    private final String stepName;
    private final String lastError;
    private final int attempts;
    private final Instant movedAt;
    private volatile boolean replayed;

    public DlqEntry(String caseId, KycState failedAtState, String stepName, String lastError, int attempts) {
        this.id = "dlq_" + UUID.randomUUID().toString().substring(0, 8);
        this.caseId = caseId;
        this.failedAtState = failedAtState;
        this.stepName = stepName;
        this.lastError = lastError;
        this.attempts = attempts;
        this.movedAt = Instant.now();
        this.replayed = false;
    }

    public String getId() { return id; }
    public String getCaseId() { return caseId; }
    public KycState getFailedAtState() { return failedAtState; }
    public String getStepName() { return stepName; }
    public String getLastError() { return lastError; }
    public int getAttempts() { return attempts; }
    public Instant getMovedAt() { return movedAt; }
    public boolean isReplayed() { return replayed; }
    public void markReplayed() { this.replayed = true; }
}
