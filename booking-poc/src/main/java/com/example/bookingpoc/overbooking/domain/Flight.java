package com.example.bookingpoc.overbooking.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "flights")
public class Flight {

    @Id
    private String code;

    @Column(nullable = false, length = 8)
    private String origin;

    @Column(nullable = false, length = 8)
    private String destination;

    @Column(nullable = false)
    private int capacity;

    /** Estimated no-show ratio (0.0 - 0.5). Drives overbookFactor. */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal noShowRate;

    /** Fraction over capacity we're willing to sell. e.g. 0.10 = +10%. */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal overbookFactor;

    @Column(nullable = false)
    private Instant departureTime;

    @Version
    private Long version;

    protected Flight() {}

    public Flight(String code, String origin, String destination, int capacity,
                  BigDecimal noShowRate, BigDecimal overbookFactor, Instant departureTime) {
        this.code = code;
        this.origin = origin;
        this.destination = destination;
        this.capacity = capacity;
        this.noShowRate = noShowRate;
        this.overbookFactor = overbookFactor;
        this.departureTime = departureTime;
    }

    public String getCode() { return code; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public int getCapacity() { return capacity; }
    public BigDecimal getNoShowRate() { return noShowRate; }
    public BigDecimal getOverbookFactor() { return overbookFactor; }
    public Instant getDepartureTime() { return departureTime; }

    public int getSellCeiling() {
        BigDecimal limit = new BigDecimal(capacity)
                .multiply(BigDecimal.ONE.add(overbookFactor));
        return limit.intValue();
    }
}
