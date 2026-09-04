package com.example.espoc.pagination.model;

import java.time.Instant;

public record ProductDoc(
        String id,
        String sku,
        String name,
        String brand,
        String category,
        long priceCents,
        int stock,
        float rating,
        Instant createdAt
) {}
