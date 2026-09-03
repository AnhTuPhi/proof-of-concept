package com.vndirect.poc.merge.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_orders")
public class TradeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Instant placedAt = Instant.now();

    public TradeOrder() {}

    public TradeOrder(Long userId, String symbol, BigDecimal quantity, BigDecimal price) {
        this.userId = userId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSymbol() { return symbol; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public Instant getPlacedAt() { return placedAt; }
}
