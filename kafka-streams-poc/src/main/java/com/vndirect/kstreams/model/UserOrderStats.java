package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record UserOrderStats(
        String userId,
        long orderCount,
        BigDecimal totalSpent,
        long windowStart,
        long windowEnd
) {
    @JsonCreator
    public UserOrderStats(
            @JsonProperty("userId") String userId,
            @JsonProperty("orderCount") long orderCount,
            @JsonProperty("totalSpent") BigDecimal totalSpent,
            @JsonProperty("windowStart") long windowStart,
            @JsonProperty("windowEnd") long windowEnd
    ) {
        this.userId = userId;
        this.orderCount = orderCount;
        this.totalSpent = totalSpent;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public static UserOrderStats empty(String userId) {
        return new UserOrderStats(userId, 0L, BigDecimal.ZERO, 0L, 0L);
    }

    public UserOrderStats accumulate(BigDecimal amount) {
        return new UserOrderStats(
                userId,
                orderCount + 1,
                totalSpent.add(amount),
                windowStart,
                windowEnd
        );
    }

    public UserOrderStats withWindow(long start, long end) {
        return new UserOrderStats(userId, orderCount, totalSpent, start, end);
    }
}
