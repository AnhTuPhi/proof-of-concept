package com.claude.kafka.cqrs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Denormalized read model projected from the orders event stream into ES.
 * Note this carries derived fields (totalAmount aggregated, status history)
 * that nobody computes server-side at query time. That's the whole point of
 * CQRS: optimize the read shape for the query, not the source-of-truth shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReadModel {
    private String orderId;
    private String customerId;
    private String status;
    private BigDecimal totalAmount;
    private String currency;
    private Instant placedAt;
    private Instant updatedAt;
    private List<StatusChange> history;

    @Data @AllArgsConstructor @NoArgsConstructor @Builder
    public static class StatusChange {
        private String status;
        private Instant at;
    }
}
