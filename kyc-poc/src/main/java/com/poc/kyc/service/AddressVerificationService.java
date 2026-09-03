package com.poc.kyc.service;

import com.poc.kyc.model.AddressVerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a third-party / government address-verification API
 * (e.g. national population database, postal service API).
 */
@Service
public class AddressVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AddressVerificationService.class);

    @Value("${kyc.address.min-delay-ms}")
    private long minDelayMs;

    @Value("${kyc.address.max-delay-ms}")
    private long maxDelayMs;

    @Value("${kyc.address.failure-rate}")
    private double failureRate;

    public AddressVerificationResult verify(String caseId, String declaredAddress, String ocrAddress) {
        long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs);
        log.info("[ADDR] starting case={} declared='{}' simulatedLatency={}ms", caseId, declaredAddress, delay);
        sleepUninterruptibly(delay);

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            String reason = pickRandomFailureReason();
            log.warn("[ADDR] FAILED case={} reason={}", caseId, reason);
            return AddressVerificationResult.failure(reason);
        }

        // Score is a fuzzy match between declared address and OCR-extracted address.
        double score = 0.40 + ThreadLocalRandom.current().nextDouble() * 0.55;
        boolean matched = score >= 0.55;
        log.info("[ADDR] OK case={} matched={} score={}", caseId, matched, "%.2f".formatted(score));
        return AddressVerificationResult.success(matched, score);
    }

    private static String pickRandomFailureReason() {
        String[] reasons = {
                "Government API rate-limited (429)",
                "Address lookup service unavailable",
                "Connection reset by peer",
                "Address database timeout"
        };
        return reasons[ThreadLocalRandom.current().nextInt(reasons.length)];
    }

    private static void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
