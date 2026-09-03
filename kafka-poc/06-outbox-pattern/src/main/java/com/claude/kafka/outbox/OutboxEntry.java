package com.claude.kafka.outbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEntry {
    private String id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private String headers;
    private String partitionKey;
    private Instant createdAt;
    private Instant publishedAt;
}
