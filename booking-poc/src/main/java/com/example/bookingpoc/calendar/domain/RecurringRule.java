package com.example.bookingpoc.calendar.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Weekly recurring availability window expressed in the owner's local time —
 * stored as wall-clock fields (NOT instants) because daylight-saving shifts
 * mean "9:00 every Monday" is not a fixed UTC offset across the year.
 *
 * Exceptions (cancelled or moved instances) live in RecurrenceException.
 */
@Entity
@Table(name = "recurring_rules")
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerId;

    /** ISO day-of-week: 1=Mon, 7=Sun. */
    @Column(nullable = false)
    private int dayOfWeek;

    @Column(nullable = false)
    private LocalTime startLocal;

    @Column(nullable = false)
    private LocalTime endLocal;

    @Column(nullable = false)
    private LocalDate validFrom;

    private LocalDate validUntil;

    protected RecurringRule() {}

    public RecurringRule(String ownerId, int dayOfWeek, LocalTime startLocal, LocalTime endLocal,
                         LocalDate validFrom, LocalDate validUntil) {
        this.ownerId = ownerId;
        this.dayOfWeek = dayOfWeek;
        this.startLocal = startLocal;
        this.endLocal = endLocal;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public Long getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public int getDayOfWeek() { return dayOfWeek; }
    public LocalTime getStartLocal() { return startLocal; }
    public LocalTime getEndLocal() { return endLocal; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
}
