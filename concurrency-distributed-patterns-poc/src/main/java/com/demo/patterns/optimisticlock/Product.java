package com.demo.patterns.optimisticlock;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private int stock;

    @Version
    private Long version;

    protected Product() {}

    public Product(String name, int stock) {
        this.name = name;
        this.stock = stock;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getStock() { return stock; }
    public Long getVersion() { return version; }

    public void decrement(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (stock < amount) throw new InsufficientStockException(id, stock, amount);
        this.stock -= amount;
    }

    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(Long id, int have, int want) {
            super("Product " + id + " has stock=" + have + ", requested=" + want);
        }
    }
}
