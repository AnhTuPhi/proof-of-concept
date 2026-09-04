package com.example.bookingpoc.overbooking.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "flight_bookings", indexes = {
        @Index(name = "idx_fb_flight_status", columnList = "flightCode,status"),
        @Index(name = "idx_fb_flight_fare", columnList = "flightCode,fareClass")
})
public class FlightBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String flightCode;

    @Column(nullable = false, length = 64)
    private String passengerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FareClass fareClass;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal farePaid;

    /** Higher means more willing to volunteer for compensation (0-100). */
    @Column(nullable = false)
    private int volunteerWillingness;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(precision = 10, scale = 2)
    private BigDecimal compensationPaid;

    protected FlightBooking() {}

    public FlightBooking(String flightCode, String passengerName, FareClass fareClass,
                         BigDecimal farePaid, int volunteerWillingness) {
        this.flightCode = flightCode;
        this.passengerName = passengerName;
        this.fareClass = fareClass;
        this.farePaid = farePaid;
        this.volunteerWillingness = volunteerWillingness;
    }

    public Long getId() { return id; }
    public String getFlightCode() { return flightCode; }
    public String getPassengerName() { return passengerName; }
    public FareClass getFareClass() { return fareClass; }
    public BigDecimal getFarePaid() { return farePaid; }
    public int getVolunteerWillingness() { return volunteerWillingness; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public BigDecimal getCompensationPaid() { return compensationPaid; }
    public void setCompensationPaid(BigDecimal compensationPaid) { this.compensationPaid = compensationPaid; }
    public Instant getCreatedAt() { return createdAt; }
}
