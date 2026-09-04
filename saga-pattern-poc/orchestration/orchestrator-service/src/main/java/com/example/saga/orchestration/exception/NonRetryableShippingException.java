package com.example.saga.orchestration.exception;

public class NonRetryableShippingException extends RuntimeException {

    public NonRetryableShippingException(String message) {
        super(message);
    }
}
