package com.example.billing.proration;

import com.example.billing.common.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "invoice_id")
    @OrderBy("id ASC")
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(Long subscriptionId, String customerId, String currency) {
        this.subscriptionId = subscriptionId;
        this.customerId = customerId;
        this.currency = currency;
        this.issuedAt = Instant.now();
        this.status = InvoiceStatus.OPEN;
    }

    public void addLineItem(InvoiceLineItem item) {
        lineItems.add(item);
    }

    public Money total() {
        return lineItems.stream()
                .map(li -> Money.of(li.getAmount(), currency))
                .reduce(Money.zero(currency), Money::add);
    }

    public void close() {
        this.status = InvoiceStatus.CLOSED;
    }

    public Long getId() {
        return id;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getCurrency() {
        return currency;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public List<InvoiceLineItem> getLineItems() {
        return Collections.unmodifiableList(lineItems);
    }

    public BigDecimal getTotalAmount() {
        return total().amount();
    }

    public enum InvoiceStatus {
        OPEN, CLOSED
    }
}
