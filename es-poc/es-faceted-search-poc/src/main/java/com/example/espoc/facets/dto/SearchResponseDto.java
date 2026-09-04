package com.example.espoc.facets.dto;

import com.example.espoc.facets.model.ProductDoc;

import java.util.List;
import java.util.Map;

public record SearchResponseDto(long totalHits, List<ProductDoc> items, Map<String, List<Bucket>> facets) {
    public record Bucket(String key, long count) {}
}
