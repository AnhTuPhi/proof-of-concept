package com.poc.kyc.service;

import com.poc.kyc.model.*;
import com.poc.kyc.repository.KycCaseRepository;
import com.poc.kyc.statemachine.KycStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the multi-step async KYC workflow.
 *
 * Each long-running step (OCR, address verify) runs on workflowExecutor.
 * On failure, retries with exponential backoff are scheduled on a
 * ScheduledExecutorService; when the per-step retry budget is exhausted
 * the case is moved to the DLQ. Every state change goes through
 * KycStateMachine — never mutate state directly.
 *
 * Self-invocation note: @Async would not take effect when calling
 * startXxx() from within this class, so we submit to the executor
 * explicitly. This is the bullet-proof way to schedule async work.
 */
@Service
public class KycWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(KycWorkflowService.class);

    private final KycStateMachine stateMachine;
    private final KycCaseRepository repository;
    private final OcrService ocrService;
    private final AddressVerificationService addressService;
    private final DocumentService documentService;
    private final Executor workflowExecutor;
    private final ScheduledExecutorService retryScheduler;

    @Value("${kyc.ocr.max-retries}")
    private int ocrMaxRetries;

    @Value("${kyc.address.max-retries}")
    private int addressMaxRetries;

    public KycWorkflowService(KycStateMachine stateMachine,
                              KycCaseRepository repository,
                              OcrService ocrService,
                              AddressVerificationService addressService,
                              DocumentService documentService,
                              @Qualifier("workflowExecutor") Executor workflowExecutor) {
        this.stateMachine = stateMachine;
        this.repository = repository;
        this.ocrService = ocrService;
        this.addressService = addressService;
        this.documentService = documentService;
        this.workflowExecutor = workflowExecutor;
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(2);
        s.setRemoveOnCancelPolicy(true);
        this.retryScheduler = s;
    }

    /* ====================  ENTRY POINTS  ==================== */

    public KycCase register(String fullName, String email, String declaredAddress) {
        KycCase c = new KycCase(fullName, email, declaredAddress);
        repository.save(c);
        log.info("[CASE] registered {} for {}", c.getId(), email);
        return c;
    }

    public KycCase uploadIdAndStart(String caseId, String fileName, long sizeBytes) {
        KycCase c = repository.findById(caseId).orElseThrow();
        Document doc = documentService.uploadDocument(caseId, fileName, "ID_CARD", sizeBytes);
        c.addDocument(doc);
        stateMachine.fire(c, KycEvent.UPLOAD_ID, "user",
                "Uploaded %s (%d bytes) -> %s".formatted(fileName, sizeBytes, doc.getS3Reference()));
        submitOcrAttempt(caseId);
        return c;
    }

    public KycCase reviewerDecision(String caseId, ReviewDecision decision, String reviewerNote, String reviewer) {
        KycCase c = repository.findById(caseId).orElseThrow();
        c.setReviewerNote(reviewerNote);
        if (decision == ReviewDecision.APPROVE) {
            stateMachine.fire(c, KycEvent.MANUAL_APPROVE, reviewer, reviewerNote);
        } else {
            c.setRejectionReason(reviewerNote);
            stateMachine.fire(c, KycEvent.MANUAL_REJECT, reviewer, reviewerNote);
        }
        return c;
    }

    public KycCase replayFromDlq(String dlqEntryId, String actor) {
        DlqEntry entry = repository.findDlqEntry(dlqEntryId).orElseThrow();
        if (entry.isReplayed()) {
            throw new IllegalStateException("DLQ entry already replayed");
        }
        KycCase c = repository.findById(entry.getCaseId()).orElseThrow();
        stateMachine.fire(c, KycEvent.REPLAY_FROM_DLQ, actor,
                "Replayed DLQ entry %s (originally failed at %s)".formatted(entry.getId(), entry.getFailedAtState()));
        // Reset attempt counters so the replayed case gets a full retry budget.
        c.resetOcrAttempts();
        c.resetAddressAttempts();
        entry.markReplayed();
        submitOcrAttempt(c.getId());
        return c;
    }

    /* ====================  OCR STEP  ==================== */

    private void submitOcrAttempt(String caseId) {
        workflowExecutor.execute(() -> runOcrAttempt(caseId));
    }

    private void runOcrAttempt(String caseId) {
        KycCase c = repository.findById(caseId).orElseThrow();

        // Move into OCR_IN_PROGRESS from whichever upstream state we're in.
        synchronized (c) {
            KycState s = c.getState();
            if (s == KycState.ID_UPLOADED) {
                stateMachine.fire(c, KycEvent.START_OCR, "system",
                        "Attempt #" + (c.getOcrAttempts() + 1));
            } else if (s == KycState.OCR_FAILED) {
                stateMachine.fire(c, KycEvent.RETRY, "retry-scheduler",
                        "Retry attempt #" + (c.getOcrAttempts() + 1));
            } else {
                log.warn("[OCR] not starting — case {} is in unexpected state {}", caseId, s);
                return;
            }
            c.incrementOcrAttempts();
        }

        String s3Ref = c.getDocuments().isEmpty() ? "n/a" : c.getDocuments().get(0).getS3Reference();
        OcrResult result;
        try {
            result = ocrService.extract(caseId, c.getFullName(), s3Ref);
        } catch (Exception e) {
            log.error("[OCR] unexpected exception for case {}", caseId, e);
            result = OcrResult.failure("Unhandled exception: " + e.getMessage());
        }
        c.setOcrResult(result);

        if (result.success()) {
            stateMachine.fire(c, KycEvent.OCR_SUCCESS, "ocr-service",
                    "confidence=%.2f idNumber=%s".formatted(result.confidence(), result.idNumber()));
            submitAddressAttempt(caseId);
        } else {
            stateMachine.fire(c, KycEvent.OCR_FAILURE, "ocr-service", result.failureReason());
            handleOcrFailure(caseId);
        }
    }

    private void handleOcrFailure(String caseId) {
        KycCase c = repository.findById(caseId).orElseThrow();
        if (c.getOcrAttempts() >= ocrMaxRetries) {
            moveToDlq(c, "OCR", c.getOcrResult().failureReason(), c.getOcrAttempts());
            return;
        }
        long backoffMs = computeBackoff(c.getOcrAttempts());
        log.info("[OCR] scheduling retry #{} for case {} in {}ms",
                c.getOcrAttempts() + 1, caseId, backoffMs);
        retryScheduler.schedule(() -> submitOcrAttempt(caseId), backoffMs, TimeUnit.MILLISECONDS);
    }

    /* ====================  ADDRESS STEP  ==================== */

    private void submitAddressAttempt(String caseId) {
        workflowExecutor.execute(() -> runAddressAttempt(caseId));
    }

    private void runAddressAttempt(String caseId) {
        KycCase c = repository.findById(caseId).orElseThrow();

        synchronized (c) {
            KycState s = c.getState();
            if (s == KycState.OCR_COMPLETED) {
                stateMachine.fire(c, KycEvent.START_ADDRESS_VERIFY, "system",
                        "Attempt #" + (c.getAddressAttempts() + 1));
            } else if (s == KycState.ADDRESS_VERIFICATION_FAILED) {
                stateMachine.fire(c, KycEvent.RETRY, "retry-scheduler",
                        "Retry attempt #" + (c.getAddressAttempts() + 1));
            } else {
                log.warn("[ADDR] not starting — case {} is in unexpected state {}", caseId, s);
                return;
            }
            c.incrementAddressAttempts();
        }

        String ocrAddress = c.getOcrResult() != null ? c.getOcrResult().addressFromId() : null;
        AddressVerificationResult result;
        try {
            result = addressService.verify(caseId, c.getDeclaredAddress(), ocrAddress);
        } catch (Exception e) {
            log.error("[ADDR] unexpected exception for case {}", caseId, e);
            result = AddressVerificationResult.failure("Unhandled exception: " + e.getMessage());
        }
        c.setAddressResult(result);

        if (result.success()) {
            stateMachine.fire(c, KycEvent.ADDRESS_VERIFY_SUCCESS, "address-service",
                    "matched=%s score=%.2f".formatted(result.matched(), result.score()));
            stateMachine.fire(c, KycEvent.SEND_TO_MANUAL_REVIEW, "system",
                    "OCR confidence=%.2f addr score=%.2f".formatted(
                            c.getOcrResult().confidence(),
                            result.score()));
        } else {
            stateMachine.fire(c, KycEvent.ADDRESS_VERIFY_FAILURE, "address-service", result.failureReason());
            handleAddressFailure(caseId);
        }
    }

    private void handleAddressFailure(String caseId) {
        KycCase c = repository.findById(caseId).orElseThrow();
        if (c.getAddressAttempts() >= addressMaxRetries) {
            moveToDlq(c, "ADDRESS_VERIFY", c.getAddressResult().failureReason(), c.getAddressAttempts());
            return;
        }
        long backoffMs = computeBackoff(c.getAddressAttempts());
        log.info("[ADDR] scheduling retry #{} for case {} in {}ms",
                c.getAddressAttempts() + 1, caseId, backoffMs);
        retryScheduler.schedule(() -> submitAddressAttempt(caseId), backoffMs, TimeUnit.MILLISECONDS);
    }

    /* ====================  DLQ  ==================== */

    private void moveToDlq(KycCase c, String stepName, String lastError, int attempts) {
        DlqEntry entry = new DlqEntry(c.getId(), c.getState(), stepName, lastError, attempts);
        repository.saveDlqEntry(entry);
        stateMachine.fire(c, KycEvent.MOVE_TO_DLQ, "system",
                "Step %s exhausted after %d attempts: %s".formatted(stepName, attempts, lastError));
        log.error("[DLQ] case {} moved — step={} attempts={} lastError={}",
                c.getId(), stepName, attempts, lastError);
    }

    /* ====================  HELPERS  ==================== */

    private long computeBackoff(int attempt) {
        long base = (long) (500L * Math.pow(2, attempt));
        long jitter = ThreadLocalRandom.current().nextLong(0, 500);
        return Math.min(base + jitter, 10_000L);
    }
}
