package com.example.bookingpoc.calendar.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * Per-date exception to a RecurringRule — either a hard cancel (the rule does
 * not generate an instance that day) or future support for time shifts.
 */
@Entity
@Table(name = "recurrence_exceptions", indexes = {
        @Index(name = "idx_rx_rule_date", columnList = "ruleId,exceptionDate", unique = true)
})
public class RecurrenceException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ruleId;

    @Column(nullable = false)
    private LocalDate exceptionDate;

    @Column(nullable = false, length = 16)
    private String kind = "CANCEL";

    protected RecurrenceException() {}

    public RecurrenceException(Long ruleId, LocalDate exceptionDate) {
        this.ruleId = ruleId;
        this.exceptionDate = exceptionDate;
    }

    public Long getId() { return id; }
    public Long getRuleId() { return ruleId; }
    public LocalDate getExceptionDate() { return exceptionDate; }
    public String getKind() { return kind; }
}
