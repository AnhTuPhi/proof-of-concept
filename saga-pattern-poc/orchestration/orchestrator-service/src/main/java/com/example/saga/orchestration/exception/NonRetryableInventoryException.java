package com.example.saga.orchestration.exception;

public class NonRetryableInventoryException extends RuntimeException {

    public NonRetryableInventoryException(String message) {
        super(message);
    }
}
