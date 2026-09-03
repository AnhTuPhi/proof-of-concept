package com.example.billing.common;

public class BillingException extends RuntimeException {
    public BillingException(String message) {
        super(message);
    }

    public BillingException(String message, Throwable cause) {
        super(message, cause);
    }
}
