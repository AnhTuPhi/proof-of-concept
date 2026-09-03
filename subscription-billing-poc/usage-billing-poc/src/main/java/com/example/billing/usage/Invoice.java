package com.example.billing.usage;

import com.example.billing.common.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "invoice_id")
    @OrderBy("id ASC")
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(String customerId, LocalDate periodStart, LocalDate periodEnd, String currency) {
        this.customerId = customerId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.currency = currency;
        this.generatedAt = Instant.now();
    }

    public void addLine(InvoiceLineItem item) {
        lineItems.add(item);
    }

    public Money total() {
        return lineItems.stream()
                .map(li -> Money.of(li.getAmount(), currency))
                .reduce(Money.zero(currency), Money::add);
    }

    public BigDecimal totalAmount() {
        return total().amount();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public List<InvoiceLineItem> getLineItems() {
        return Collections.unmodifiableList(lineItems);
    }
}
