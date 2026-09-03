package com.poc.kyc.service;

import com.poc.kyc.model.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a third-party OCR service (e.g. Mindee, AWS Textract).
 * Returns success/failure stochastically based on configured failure-rate.
 * Real implementation would HTTP-POST the S3 document URL and parse the response.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    @Value("${kyc.ocr.min-delay-ms}")
    private long minDelayMs;

    @Value("${kyc.ocr.max-delay-ms}")
    private long maxDelayMs;

    @Value("${kyc.ocr.failure-rate}")
    private double failureRate;

    public OcrResult extract(String caseId, String declaredName, String s3DocumentRef) {
        long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs);
        log.info("[OCR] starting case={} s3={} simulatedLatency={}ms", caseId, s3DocumentRef, delay);
        sleepUninterruptibly(delay);

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            String reason = pickRandomFailureReason();
            log.warn("[OCR] FAILED case={} reason={}", caseId, reason);
            return OcrResult.failure(reason);
        }

        // Synthesize a believable OCR extraction. Confidence varies — drives
        // downstream auto-approve vs manual-review heuristics.
        double confidence = 0.65 + ThreadLocalRandom.current().nextDouble() * 0.30;
        String idNumber = "ID" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String dob = "199%d-0%d-1%d".formatted(
                ThreadLocalRandom.current().nextInt(0, 10),
                ThreadLocalRandom.current().nextInt(1, 10),
                ThreadLocalRandom.current().nextInt(0, 10));
        String address = "%d %s, District %d, Hanoi".formatted(
                ThreadLocalRandom.current().nextInt(1, 999),
                pickStreet(),
                ThreadLocalRandom.current().nextInt(1, 12));
        OcrResult result = OcrResult.success(declaredName, idNumber, dob, address, confidence);
        log.info("[OCR] OK case={} idNumber={} confidence={}", caseId, idNumber, "%.2f".formatted(confidence));
        return result;
    }

    private static String pickRandomFailureReason() {
        String[] reasons = {
                "Image too blurry to extract text",
                "OCR service timeout (5s)",
                "Document type not recognized",
                "OCR engine returned 503",
                "Glare detected on ID card"
        };
        return reasons[ThreadLocalRandom.current().nextInt(reasons.length)];
    }

    private static String pickStreet() {
        String[] streets = {"Tran Hung Dao", "Le Loi", "Nguyen Trai", "Pham Hung", "Lac Long Quan", "Hoang Quoc Viet"};
        return streets[ThreadLocalRandom.current().nextInt(streets.length)];
    }

    private static void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
