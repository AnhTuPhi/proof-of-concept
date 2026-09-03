package com.demo.patterns.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "outbox_order")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customer;

    private String product;

    private int quantity;

    private Instant createdAt;

    protected OrderEntity() {}

    public OrderEntity(String customer, String product, int quantity) {
        this.customer = customer;
        this.product = product;
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCustomer() { return customer; }
    public String getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public Instant getCreatedAt() { return createdAt; }
}
