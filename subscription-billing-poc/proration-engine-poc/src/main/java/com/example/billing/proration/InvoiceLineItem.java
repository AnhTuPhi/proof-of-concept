package com.example.billing.proration;

import com.example.billing.common.Money;
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
@Table(name = "invoice_line_items")
public class InvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256)
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LineItemType type;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "plan_id")
    private String planId;

    protected InvoiceLineItem() {
    }

    private InvoiceLineItem(String description, Money amount, LineItemType type,
                            LocalDate periodStart, LocalDate periodEnd, String planId) {
        this.description = description;
        this.amount = amount.amount();
        this.type = type;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.planId = planId;
    }

    public static InvoiceLineItem charge(String description, Money amount,
                                         LocalDate start, LocalDate end, String planId) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Charge must be non-negative, got " + amount);
        }
        return new InvoiceLineItem(description, amount, LineItemType.CHARGE, start, end, planId);
    }

    public static InvoiceLineItem credit(String description, Money amount,
                                         LocalDate start, LocalDate end, String planId) {
        Money signed = amount.isPositive() ? amount.negate() : amount;
        return new InvoiceLineItem(description, signed, LineItemType.CREDIT, start, end, planId);
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LineItemType getType() {
        return type;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getPlanId() {
        return planId;
    }

    public enum LineItemType {
        CHARGE, CREDIT
    }
}
