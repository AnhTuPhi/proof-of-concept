package com.poc.kyc.model;

import java.time.Instant;
import java.util.UUID;

public class Document {
    private final String id;
    private final String caseId;
    private final String fileName;
    private final String docType;
    private final String s3Reference;
    private final long sizeBytes;
    private final Instant uploadedAt;

    public Document(String caseId, String fileName, String docType, String s3Reference, long sizeBytes) {
        this.id = "doc_" + UUID.randomUUID().toString().substring(0, 8);
        this.caseId = caseId;
        this.fileName = fileName;
        this.docType = docType;
        this.s3Reference = s3Reference;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getCaseId() { return caseId; }
    public String getFileName() { return fileName; }
    public String getDocType() { return docType; }
    public String getS3Reference() { return s3Reference; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getUploadedAt() { return uploadedAt; }
}
