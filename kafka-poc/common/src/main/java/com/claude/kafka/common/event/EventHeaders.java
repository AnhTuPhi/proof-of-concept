package com.claude.kafka.common.event;

/**
 * Standard Kafka headers used across the platform.
 * <p>
 * Why headers and not payload fields?
 *   - Brokers can route on headers without deserializing the payload.
 *   - Consumers can filter without paying serialization cost.
 *   - DLQ tooling can read metadata even when the payload is corrupt.
 */
public final class EventHeaders {
    private EventHeaders() {}

    public static final String EVENT_ID     = "event-id";
    public static final String EVENT_TYPE   = "event-type";
    public static final String TRACE_ID     = "trace-id";
    public static final String SOURCE_APP   = "source-app";
    public static final String SCHEMA_VER   = "schema-version";

    // Retry / DLQ metadata
    public static final String RETRY_COUNT       = "retry-count";
    public static final String ORIGINAL_TOPIC    = "original-topic";
    public static final String ORIGINAL_OFFSET   = "original-offset";
    public static final String ORIGINAL_PARTITION = "original-partition";
    public static final String EXCEPTION_CLASS   = "exception-class";
    public static final String EXCEPTION_MESSAGE = "exception-message";
    public static final String DLQ_TIMESTAMP     = "dlq-timestamp";
    public static final String NEXT_RETRY_AT_MS  = "next-retry-at-ms";
}
