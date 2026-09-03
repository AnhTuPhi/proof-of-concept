package com.example.billing.proration;

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
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(name = "pending_plan_id")
    private String pendingPlanId;

    protected Subscription() {
    }

    public Subscription(String customerId, String planId, LocalDate periodStart, LocalDate periodEnd) {
        this.customerId = customerId;
        this.planId = planId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = SubscriptionStatus.ACTIVE;
    }

    public void switchPlanImmediately(String newPlanId, LocalDate effectiveDate, LocalDate newPeriodEnd) {
        this.planId = newPlanId;
        this.periodStart = effectiveDate;
        this.periodEnd = newPeriodEnd;
        this.pendingPlanId = null;
    }

    public void schedulePlanChange(String newPlanId) {
        this.pendingPlanId = newPlanId;
    }

    public void applyPendingPlan(LocalDate newPeriodStart, LocalDate newPeriodEnd) {
        if (pendingPlanId == null) {
            throw new IllegalStateException("No pending plan to apply");
        }
        this.planId = pendingPlanId;
        this.pendingPlanId = null;
        this.periodStart = newPeriodStart;
        this.periodEnd = newPeriodEnd;
    }

    public void cancel() {
        this.status = SubscriptionStatus.CANCELED;
        this.pendingPlanId = null;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getPlanId() {
        return planId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public String getPendingPlanId() {
        return pendingPlanId;
    }

    public enum SubscriptionStatus {
        ACTIVE, CANCELED
    }
}
