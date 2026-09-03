package com.poc.kyc.model;

import java.util.Set;

/**
 * KYC case state machine states.
 * Terminal states: APPROVED, REJECTED, DEAD_LETTER.
 */
public enum KycState {
    REGISTERED("User registered, awaiting ID upload"),
    ID_UPLOADED("ID documents uploaded, awaiting OCR"),
    OCR_IN_PROGRESS("OCR extraction in progress"),
    OCR_COMPLETED("OCR completed successfully"),
    OCR_FAILED("OCR extraction failed, will retry"),
    ADDRESS_VERIFICATION_IN_PROGRESS("Address verification in progress"),
    ADDRESS_VERIFIED("Address verified successfully"),
    ADDRESS_VERIFICATION_FAILED("Address verification failed, will retry"),
    PENDING_MANUAL_REVIEW("Awaiting manual reviewer decision"),
    APPROVED("KYC approved"),
    REJECTED("KYC rejected"),
    DEAD_LETTER("Moved to DLQ after exhausted retries");

    private final String description;

    KycState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == DEAD_LETTER;
    }

    public boolean isFailureState() {
        return this == OCR_FAILED || this == ADDRESS_VERIFICATION_FAILED;
    }

    public static Set<KycState> terminalStates() {
        return Set.of(APPROVED, REJECTED, DEAD_LETTER);
    }
}
