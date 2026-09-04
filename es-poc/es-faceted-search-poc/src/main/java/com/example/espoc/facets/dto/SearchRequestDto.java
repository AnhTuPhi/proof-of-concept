package com.example.espoc.facets.dto;

public record SearchRequestDto(
        String q,
        String brand,
        String category,
        String priceBucket,   // "0-10" | "10-50" | "50-200" | "200+"
        Integer minRating,
        int size
) {
    public SearchRequestDto {
        if (size <= 0) size = 20;
    }
}
