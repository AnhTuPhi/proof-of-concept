package com.poc.kyc.model;

public record OcrResult(
        boolean success,
        String fullName,
        String idNumber,
        String dateOfBirth,
        String addressFromId,
        double confidence,
        String failureReason
) {
    public static OcrResult success(String name, String idNumber, String dob, String address, double confidence) {
        return new OcrResult(true, name, idNumber, dob, address, confidence, null);
    }

    public static OcrResult failure(String reason) {
        return new OcrResult(false, null, null, null, null, 0.0, reason);
    }
}
