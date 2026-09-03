package com.poc.kyc.model;

public record AddressVerificationResult(
        boolean success,
        boolean matched,
        double score,
        String failureReason
) {
    public static AddressVerificationResult success(boolean matched, double score) {
        return new AddressVerificationResult(true, matched, score, null);
    }

    public static AddressVerificationResult failure(String reason) {
        return new AddressVerificationResult(false, false, 0.0, reason);
    }
}
