package com.example.espoc.cons.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "products", schema = "consistency")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductEntity {
    @Id @Column(length = 40)             private String id;
    @Column(length = 64, nullable = false) private String sku;
    @Column(length = 255, nullable = false) private String name;
    @Column(nullable = false)             private long priceCents;
    @Column(nullable = false)             private Instant updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
}
