package com.example.billing.dunning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "renewal_attempts")
public class RenewalAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "scheduled_on", nullable = false)
    private LocalDate scheduledOn;

    @Column(name = "ran_on")
    private LocalDate ranOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "failure_reason")
    private String failureReason;

    protected RenewalAttempt() {
    }

    public RenewalAttempt(Long subscriptionId, int attemptNumber, LocalDate scheduledOn) {
        this.subscriptionId = subscriptionId;
        this.attemptNumber = attemptNumber;
        this.scheduledOn = scheduledOn;
        this.status = Status.SCHEDULED;
    }

    public void markSucceeded(LocalDate today) {
        this.ranOn = today;
        this.status = Status.SUCCEEDED;
    }

    public void markFailed(LocalDate today, String reason) {
        this.ranOn = today;
        this.status = Status.FAILED;
        this.failureReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public LocalDate getScheduledOn() {
        return scheduledOn;
    }

    public LocalDate getRanOn() {
        return ranOn;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public enum Status {
        SCHEDULED, SUCCEEDED, FAILED
    }
}
