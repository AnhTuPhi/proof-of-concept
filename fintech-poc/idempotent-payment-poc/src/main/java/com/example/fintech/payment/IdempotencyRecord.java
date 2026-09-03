package com.example.fintech.payment;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"))
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private RecordStatus status;

    @Column(length = 4000)
    private String responseJson;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant completedAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String idempotencyKey, String requestFingerprint) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = RecordStatus.IN_FLIGHT;
        this.createdAt = Instant.now();
    }

    public void complete(String responseJson) {
        this.responseJson = responseJson;
        this.status = RecordStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = RecordStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public RecordStatus getStatus() { return status; }
    public String getResponseJson() { return responseJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public enum RecordStatus { IN_FLIGHT, COMPLETED, FAILED }
}
