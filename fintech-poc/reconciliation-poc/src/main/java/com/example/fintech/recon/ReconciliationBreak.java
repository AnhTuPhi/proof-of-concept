package com.example.fintech.recon;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "recon_breaks")
public class ReconciliationBreak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private BreakType type;

    @Column(length = 64)
    private String internalTxnId;

    @Column(length = 64)
    private String providerTxnId;

    @Column(length = 256)
    private String detail;

    @Column(nullable = false)
    private Instant detectedAt;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private BreakStatus status;

    protected ReconciliationBreak() {}

    public ReconciliationBreak(BreakType type, String internalId, String providerId, String detail) {
        this.type = type;
        this.internalTxnId = internalId;
        this.providerTxnId = providerId;
        this.detail = detail;
        this.detectedAt = Instant.now();
        this.status = BreakStatus.OPEN;
    }

    public void resolve() { this.status = BreakStatus.RESOLVED; }

    public Long getId() { return id; }
    public BreakType getType() { return type; }
    public String getInternalTxnId() { return internalTxnId; }
    public String getProviderTxnId() { return providerTxnId; }
    public String getDetail() { return detail; }
    public Instant getDetectedAt() { return detectedAt; }
    public BreakStatus getStatus() { return status; }

    public enum BreakType {
        MISSING_AT_PROVIDER,
        MISSING_AT_INTERNAL,
        AMOUNT_MISMATCH,
        CURRENCY_MISMATCH
    }

    public enum BreakStatus { OPEN, RESOLVED }
}
