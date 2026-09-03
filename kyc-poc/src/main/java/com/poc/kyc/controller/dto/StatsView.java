package com.poc.kyc.controller.dto;

import java.util.Map;

public record StatsView(
        long totalCases,
        long approved,
        long rejected,
        long pendingReview,
        long inProgress,
        long deadLetter,
        Map<String, Long> byState
) {}
