package com.example.espoc.sync.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record ProductDto(
        String id,
        @NotBlank String sku,
        @NotBlank String name,
        String description,
        @Min(0) long priceCents,
        @Min(0) int stock,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    /** Constructor for incoming write requests (server fills id/timestamps/version). */
    public static ProductDto incoming(String sku, String name, String desc, long priceCents, int stock) {
        return new ProductDto(null, sku, name, desc, priceCents, stock, 0L, null, null);
    }
}
