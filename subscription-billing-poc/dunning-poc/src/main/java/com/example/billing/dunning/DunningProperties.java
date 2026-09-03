package com.example.billing.dunning;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "dunning")
public record DunningProperties(
        List<Integer> retryOffsetsDays,
        int gracePeriodDays,
        int suspensionWindowDays
) {
}
