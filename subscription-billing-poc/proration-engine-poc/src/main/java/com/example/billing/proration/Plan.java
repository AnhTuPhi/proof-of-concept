package com.example.billing.proration;

import com.example.billing.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "plans")
public class Plan {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected Plan() {
    }

    public Plan(String id, String name, Money monthlyPrice) {
        this.id = id;
        this.name = name;
        this.priceAmount = monthlyPrice.amount();
        this.currency = monthlyPrice.currencyCode();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Money monthlyPrice() {
        return Money.of(priceAmount, currency);
    }
}
