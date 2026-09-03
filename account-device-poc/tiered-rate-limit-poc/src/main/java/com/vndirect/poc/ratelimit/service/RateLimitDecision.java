package com.vndirect.poc.ratelimit.service;

import java.util.List;

public record RateLimitDecision(
    boolean allowed,
    long retryAfterMillis,
    String violatedDimension,
    List<DimensionState> dimensions
) {
    public record DimensionState(String dimension, String key, String tier, long remaining, long capacity) {}
}
