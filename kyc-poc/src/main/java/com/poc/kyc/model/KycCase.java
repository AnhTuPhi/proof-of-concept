package com.poc.kyc.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggregate root for one KYC application.
 * State changes go through the KycStateMachine — do NOT mutate state directly.
 */
public class KycCase {

    private final String id;
    private final String fullName;
    private final String email;
    private final String declaredAddress;
    private final Instant createdAt;

    private volatile KycState state;
    private volatile Instant updatedAt;

    private final List<Document> documents = Collections.synchronizedList(new ArrayList<>());
    private final List<AuditEntry> auditLog = Collections.synchronizedList(new ArrayList<>());

    private volatile OcrResult ocrResult;
    private volatile AddressVerificationResult addressResult;

    private final AtomicInteger ocrAttempts = new AtomicInteger(0);
    private final AtomicInteger addressAttempts = new AtomicInteger(0);

    private volatile String rejectionReason;
    private volatile String reviewerNote;

    public KycCase(String fullName, String email, String declaredAddress) {
        this.id = "kyc_" + UUID.randomUUID().toString().substring(0, 12);
        this.fullName = fullName;
        this.email = email;
        this.declaredAddress = declaredAddress;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.state = KycState.REGISTERED;
        this.auditLog.add(AuditEntry.of(null, KycState.REGISTERED, null, "system", "Case created"));
    }

    public String getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getDeclaredAddress() { return declaredAddress; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public KycState getState() { return state; }
    public List<Document> getDocuments() { return Collections.unmodifiableList(documents); }
    public List<AuditEntry> getAuditLog() { return Collections.unmodifiableList(auditLog); }
    public OcrResult getOcrResult() { return ocrResult; }
    public AddressVerificationResult getAddressResult() { return addressResult; }
    public int getOcrAttempts() { return ocrAttempts.get(); }
    public int getAddressAttempts() { return addressAttempts.get(); }
    public String getRejectionReason() { return rejectionReason; }
    public String getReviewerNote() { return reviewerNote; }

    public void addDocument(Document d) {
        documents.add(d);
    }

    public void addAuditEntry(AuditEntry e) {
        auditLog.add(e);
    }

    public void setState(KycState newState) {
        this.state = newState;
        this.updatedAt = Instant.now();
    }

    public void setOcrResult(OcrResult r) { this.ocrResult = r; }
    public void setAddressResult(AddressVerificationResult r) { this.addressResult = r; }
    public int incrementOcrAttempts() { return ocrAttempts.incrementAndGet(); }
    public int incrementAddressAttempts() { return addressAttempts.incrementAndGet(); }
    public void resetOcrAttempts() { ocrAttempts.set(0); }
    public void resetAddressAttempts() { addressAttempts.set(0); }
    public void setRejectionReason(String r) { this.rejectionReason = r; }
    public void setReviewerNote(String n) { this.reviewerNote = n; }
}
