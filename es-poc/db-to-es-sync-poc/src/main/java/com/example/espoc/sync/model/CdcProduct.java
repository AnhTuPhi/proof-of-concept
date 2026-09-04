package com.example.espoc.sync.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "products", schema = "sync_cdc")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CdcProduct {
    @Id @Column(length = 40)             private String id;
    @Column(length = 64, nullable = false) private String sku;
    @Column(length = 255, nullable = false) private String name;
    @Column(columnDefinition = "TEXT")    private String description;
    @Column(nullable = false)             private long priceCents;
    @Column(nullable = false)             private int stock;
    @Version @Column(nullable = false)    private long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false)             private Instant updatedAt;

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
