package com.example.billing.dunning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false, length = 4)
    private String last4;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Column(name = "force_decline_reason")
    private String forceDeclineReason;

    protected PaymentMethod() {
    }

    public PaymentMethod(String customerId, String brand, String last4, LocalDate expiresOn) {
        this.customerId = customerId;
        this.brand = brand;
        this.last4 = last4;
        this.expiresOn = expiresOn;
    }

    public boolean canCharge(LocalDate today) {
        return forceDeclineReason == null && !today.isAfter(expiresOn);
    }

    public String declineReason(LocalDate today) {
        if (forceDeclineReason != null) return forceDeclineReason;
        if (today.isAfter(expiresOn)) return "expired_card";
        return null;
    }

    public void forceDecline(String reason) {
        this.forceDeclineReason = reason;
    }

    public void replaceWith(String brand, String last4, LocalDate expiresOn) {
        this.brand = brand;
        this.last4 = last4;
        this.expiresOn = expiresOn;
        this.forceDeclineReason = null;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getBrand() {
        return brand;
    }

    public String getLast4() {
        return last4;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public String getForceDeclineReason() {
        return forceDeclineReason;
    }
}
