package com.example.saga.choreography.shipping.domain;

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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
