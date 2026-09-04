package com.example.bookingpoc.seat.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "hard_holds", indexes = {
        @Index(name = "idx_hh_seat", columnList = "seatId"),
        @Index(name = "idx_hh_status_deadline", columnList = "status,paymentDeadline")
})
public class HardHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long seatId;

    @Column(nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false)
    private Instant paymentDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HardHoldStatus status = HardHoldStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected HardHold() {}

    public HardHold(Long seatId, String customerId, Instant paymentDeadline) {
        this.seatId = seatId;
        this.customerId = customerId;
        this.paymentDeadline = paymentDeadline;
    }

    public Long getId() { return id; }
    public Long getSeatId() { return seatId; }
    public String getCustomerId() { return customerId; }
    public Instant getPaymentDeadline() { return paymentDeadline; }
    public HardHoldStatus getStatus() { return status; }
    public void setStatus(HardHoldStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
