package com.example.saga.orchestration.exception;

/**
 * Thrown when a payment fails for a business reason that retrying will not fix
 * (e.g. insufficient funds, card declined). Listed in the workflow's retry policy's
 * doNotRetry list so Temporal aborts immediately and the saga compensates.
 */
public class NonRetryablePaymentException extends RuntimeException {

    public NonRetryablePaymentException(String message) {
        super(message);
    }
}
