package com.vndirect.poc.merge.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watchlists")
public class Watchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String symbols;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Watchlist() {}

    public Watchlist(Long userId, String name, String symbols) {
        this.userId = userId;
        this.name = name;
        this.symbols = symbols;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public String getSymbols() { return symbols; }
    public Instant getCreatedAt() { return createdAt; }
}
