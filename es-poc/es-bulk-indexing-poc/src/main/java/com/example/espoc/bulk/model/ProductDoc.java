package com.example.espoc.bulk.model;

import java.time.Instant;

public record ProductDoc(
        String id, String sku, String name, String description,
        long priceCents, int stock, Instant createdAt
) {}
