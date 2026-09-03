package com.poc.kyc.model;

/**
 * Events that drive transitions in the KYC state machine.
 */
public enum KycEvent {
    UPLOAD_ID,
    START_OCR,
    OCR_SUCCESS,
    OCR_FAILURE,
    START_ADDRESS_VERIFY,
    ADDRESS_VERIFY_SUCCESS,
    ADDRESS_VERIFY_FAILURE,
    SEND_TO_MANUAL_REVIEW,
    MANUAL_APPROVE,
    MANUAL_REJECT,
    RETRY,
    MOVE_TO_DLQ,
    REPLAY_FROM_DLQ
}
