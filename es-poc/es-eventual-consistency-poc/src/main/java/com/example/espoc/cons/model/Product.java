package com.example.espoc.cons.model;

import java.time.Instant;

public record Product(String id, String sku, String name, long priceCents, Instant updatedAt) {}
