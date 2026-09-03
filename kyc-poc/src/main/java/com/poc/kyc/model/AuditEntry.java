package com.poc.kyc.model;

import java.time.Instant;

/**
 * Immutable audit-log entry recording one transition in the workflow.
 */
public record AuditEntry(
        Instant timestamp,
        KycState fromState,
        KycState toState,
        KycEvent event,
        String actor,
        String detail
) {
    public static AuditEntry of(KycState from, KycState to, KycEvent event, String actor, String detail) {
        return new AuditEntry(Instant.now(), from, to, event, actor, detail);
    }
}
