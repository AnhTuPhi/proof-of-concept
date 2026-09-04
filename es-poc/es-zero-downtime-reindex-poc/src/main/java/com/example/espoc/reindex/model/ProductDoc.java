package com.example.espoc.reindex.model;

import java.time.Instant;

public record ProductDoc(
        String id, String sku, String name, String description, long priceCents, Instant createdAt
) {}
