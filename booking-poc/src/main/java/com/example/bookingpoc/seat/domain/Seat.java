package com.example.bookingpoc.seat.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    private Long id;

    @Column(nullable = false, length = 16)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatType type;

    @Column(nullable = false, length = 32)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    private Long version;

    protected Seat() {}

    public Seat(Long id, String code, SeatType type, String resourceId) {
        this.id = id;
        this.code = code;
        this.type = type;
        this.resourceId = resourceId;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public SeatType getType() { return type; }
    public String getResourceId() { return resourceId; }
    public SeatStatus getStatus() { return status; }
    public void setStatus(SeatStatus status) { this.status = status; }
    public Long getVersion() { return version; }
}
