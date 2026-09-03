package com.example.billing.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_line_items")
public class InvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UsageMetric metric;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal quantity;

    @Column(name = "price_per_unit", nullable = false, precision = 19, scale = 8)
    private BigDecimal pricePerUnit;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected InvoiceLineItem() {
    }

    public InvoiceLineItem(String description, UsageMetric metric,
                           BigDecimal quantity, BigDecimal pricePerUnit, BigDecimal amount) {
        this.description = description;
        this.metric = metric;
        this.quantity = quantity;
        this.pricePerUnit = pricePerUnit;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public UsageMetric getMetric() {
        return metric;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPricePerUnit() {
        return pricePerUnit;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
