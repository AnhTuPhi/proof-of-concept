package com.poc.kyc.controller.dto;

import com.poc.kyc.model.*;

import java.time.Instant;
import java.util.List;

/**
 * Read-model projection of a KycCase for the dashboard.
 */
public record CaseView(
        String id,
        String fullName,
        String email,
        String declaredAddress,
        KycState state,
        String stateDescription,
        Instant createdAt,
        Instant updatedAt,
        int ocrAttempts,
        int addressAttempts,
        OcrResult ocrResult,
        AddressVerificationResult addressResult,
        String rejectionReason,
        String reviewerNote,
        List<DocumentView> documents,
        List<AuditEntry> auditLog
) {
    public static CaseView from(KycCase c) {
        return new CaseView(
                c.getId(),
                c.getFullName(),
                c.getEmail(),
                c.getDeclaredAddress(),
                c.getState(),
                c.getState().getDescription(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getOcrAttempts(),
                c.getAddressAttempts(),
                c.getOcrResult(),
                c.getAddressResult(),
                c.getRejectionReason(),
                c.getReviewerNote(),
                c.getDocuments().stream().map(DocumentView::from).toList(),
                c.getAuditLog()
        );
    }

    public record DocumentView(String id, String fileName, String docType, String s3Reference, long sizeBytes, Instant uploadedAt) {
        public static DocumentView from(Document d) {
            return new DocumentView(d.getId(), d.getFileName(), d.getDocType(), d.getS3Reference(), d.getSizeBytes(), d.getUploadedAt());
        }
    }
}
