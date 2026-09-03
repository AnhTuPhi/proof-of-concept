package com.claude.kafka.common.error;

/**
 * Marks an exception as <strong>non-retriable</strong>: the message is malformed,
 * the schema is incompatible, or the business rule is violated and no amount
 * of retrying will fix it. Consumer pipelines short-circuit straight to the
 * DLQ when they see this.
 */
public class PoisonMessageException extends RuntimeException {
    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
