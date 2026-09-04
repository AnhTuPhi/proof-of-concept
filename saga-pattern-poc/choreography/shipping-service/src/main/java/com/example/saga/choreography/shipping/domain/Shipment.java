package com.example.saga.choreography.shipping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "shipments")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Shipment {

    public enum Status { SCHEDULED, FAILED }

    @Id
    @Column(name = "shipment_id", length = 64)
    private String shipmentId;

    @Column(name = "saga_id", length = 64, nullable = false, unique = true)
    private String sagaId;

    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    @Column(name = "estimated_delivery")
    private Instant estimatedDelivery;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
