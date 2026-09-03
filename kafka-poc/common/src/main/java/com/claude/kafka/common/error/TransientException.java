package com.claude.kafka.common.error;

/**
 * Marks an exception as <strong>retriable</strong>: a downstream timeout,
 * a temporary lock, a 5xx from an external API. Retry topics will pick this up.
 */
public class TransientException extends RuntimeException {
    public TransientException(String message) {
        super(message);
    }

    public TransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
