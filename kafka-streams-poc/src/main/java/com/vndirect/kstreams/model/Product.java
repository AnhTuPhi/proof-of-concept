package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record Product(
        String productId,
        String name,
        String category,
        BigDecimal basePrice
) {
    @JsonCreator
    public Product(
            @JsonProperty("productId") String productId,
            @JsonProperty("name") String name,
            @JsonProperty("category") String category,
            @JsonProperty("basePrice") BigDecimal basePrice
    ) {
        this.productId = productId;
        this.name = name;
        this.category = category;
        this.basePrice = basePrice;
    }
}
