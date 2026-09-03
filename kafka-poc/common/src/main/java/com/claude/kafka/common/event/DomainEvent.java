package com.claude.kafka.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope every domain event in the system is wrapped in.
 * <p>
 * The fields here are the bare minimum that production teams end up needing:
 * <ul>
 *   <li>{@code eventId} — for idempotent consumers; also used as Kafka message header.</li>
 *   <li>{@code aggregateId} — used as the Kafka partition key so events for the
 *       same aggregate stay ordered on a single partition.</li>
 *   <li>{@code occurredAt} — when the event happened in the source system,
 *       not when it landed in Kafka.</li>
 *   <li>{@code traceId} — for correlation across services. Populated from MDC.</li>
 *   <li>{@code schemaVersion} — explicit version for forward compatibility,
 *       independent of Schema Registry's wire format.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DomainEvent<T> {
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private Instant occurredAt;
    private String traceId;
    private int schemaVersion;
    private T payload;

    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    public static <T> DomainEvent<T> of(String eventType, String aggregateType,
                                        String aggregateId, T payload) {
        return DomainEvent.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .occurredAt(Instant.now())
                .schemaVersion(1)
                .payload(payload)
                .build();
    }
}
