package com.example.billing.dunning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "plan_name", nullable = false)
    private String planName;

    @Column(name = "monthly_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "payment_method_id", nullable = false)
    private Long paymentMethodId;

    @Column(name = "next_renewal_on", nullable = false)
    private LocalDate nextRenewalOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "suspended_on")
    private LocalDate suspendedOn;

    @Column(name = "scheduled_suspension_on")
    private LocalDate scheduledSuspensionOn;

    @Column(name = "canceled_on")
    private LocalDate canceledOn;

    protected Subscription() {
    }

    public Subscription(String customerId, String planName, BigDecimal monthlyAmount,
                        Long paymentMethodId, LocalDate nextRenewalOn) {
        this.customerId = customerId;
        this.planName = planName;
        this.monthlyAmount = monthlyAmount;
        this.paymentMethodId = paymentMethodId;
        this.nextRenewalOn = nextRenewalOn;
        this.status = Status.ACTIVE;
    }

    public void advanceRenewal() {
        this.nextRenewalOn = nextRenewalOn.plusMonths(1);
        this.status = Status.ACTIVE;
    }

    public void enterPastDue() {
        this.status = Status.PAST_DUE;
    }

    public void scheduleSuspension(LocalDate on) {
        this.scheduledSuspensionOn = on;
    }

    public void suspend(LocalDate today) {
        this.status = Status.SUSPENDED;
        this.suspendedOn = today;
        this.scheduledSuspensionOn = null;
    }

    public void cancel(LocalDate today) {
        this.status = Status.CANCELED;
        this.canceledOn = today;
    }

    public void reactivate(LocalDate nextRenewal) {
        this.status = Status.ACTIVE;
        this.suspendedOn = null;
        this.scheduledSuspensionOn = null;
        this.nextRenewalOn = nextRenewal;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getPlanName() {
        return planName;
    }

    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getPaymentMethodId() {
        return paymentMethodId;
    }

    public LocalDate getNextRenewalOn() {
        return nextRenewalOn;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDate getSuspendedOn() {
        return suspendedOn;
    }

    public LocalDate getScheduledSuspensionOn() {
        return scheduledSuspensionOn;
    }

    public LocalDate getCanceledOn() {
        return canceledOn;
    }

    public enum Status {
        ACTIVE, PAST_DUE, SUSPENDED, CANCELED
    }
}
