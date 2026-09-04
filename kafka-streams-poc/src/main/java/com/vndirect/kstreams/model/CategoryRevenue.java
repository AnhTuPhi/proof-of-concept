package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record CategoryRevenue(
        String category,
        long orderCount,
        BigDecimal totalRevenue,
        long windowStart,
        long windowEnd
) {
    @JsonCreator
    public CategoryRevenue(
            @JsonProperty("category") String category,
            @JsonProperty("orderCount") long orderCount,
            @JsonProperty("totalRevenue") BigDecimal totalRevenue,
            @JsonProperty("windowStart") long windowStart,
            @JsonProperty("windowEnd") long windowEnd
    ) {
        this.category = category;
        this.orderCount = orderCount;
        this.totalRevenue = totalRevenue;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public static CategoryRevenue empty(String category) {
        return new CategoryRevenue(category, 0L, BigDecimal.ZERO, 0L, 0L);
    }

    public CategoryRevenue accumulate(BigDecimal amount) {
        return new CategoryRevenue(
                this.category,
                this.orderCount + 1,
                this.totalRevenue.add(amount),
                this.windowStart,
                this.windowEnd
        );
    }

    public CategoryRevenue withWindow(long start, long end) {
        return new CategoryRevenue(category, orderCount, totalRevenue, start, end);
    }
}
