package com.example.saga.choreography.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Local copy of the originating-order fields that this service needs to act
 * on later events. Populated when {@code OrderCreated} is observed so the
 * service is self-sufficient and does not have to call back to order-service
 * (which would couple the choreography). One row per saga.
 */
@Entity
@Table(name = "saga_context")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SagaContext {

    @Id
    @Column(name = "saga_id", length = 64)
    private String sagaId;

    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "product_id", length = 64, nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
