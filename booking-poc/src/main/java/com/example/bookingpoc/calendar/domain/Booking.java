package com.example.bookingpoc.calendar.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "calendar_bookings", indexes = {
        @Index(name = "idx_b_owner_range", columnList = "ownerId,startUtc,endUtc"),
        @Index(name = "idx_b_status", columnList = "status")
})
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String inviteeName;

    @Column(nullable = false)
    private Instant startUtc;

    @Column(nullable = false)
    private Instant endUtc;

    @Column(nullable = false, length = 64)
    private String inviteeTimezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Booking() {}

    public Booking(String ownerId, String inviteeName, Instant startUtc, Instant endUtc, String inviteeTimezone) {
        this.ownerId = ownerId;
        this.inviteeName = inviteeName;
        this.startUtc = startUtc;
        this.endUtc = endUtc;
        this.inviteeTimezone = inviteeTimezone;
    }

    public Long getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getInviteeName() { return inviteeName; }
    public Instant getStartUtc() { return startUtc; }
    public Instant getEndUtc() { return endUtc; }
    public String getInviteeTimezone() { return inviteeTimezone; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
