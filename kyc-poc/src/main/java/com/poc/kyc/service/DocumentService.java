package com.poc.kyc.service;

import com.poc.kyc.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Simulates uploading a document to S3/MinIO and returning an immutable reference.
 * In a real system this would push bytes to MinIO using the S3 SDK and return the
 * object key — the workflow only ever stores the reference, never the bytes,
 * which keeps the case aggregate small and the audit log clean.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public Document uploadDocument(String caseId, String fileName, String docType, long sizeBytes) {
        String s3Ref = "s3://kyc-docs/%s/%s_%s".formatted(
                caseId,
                UUID.randomUUID().toString().substring(0, 8),
                fileName);
        Document doc = new Document(caseId, fileName, docType, s3Ref, sizeBytes);
        log.info("[DOC] uploaded case={} doc={} s3={}", caseId, doc.getId(), s3Ref);
        return doc;
    }
}
