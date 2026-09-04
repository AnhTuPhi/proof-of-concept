package com.example.espoc.vn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.vietnamese")
public record VnProperties(
        String standardIndex, String foldedIndex, String icuIndex,
        boolean icuEnabled, int sampleSize
) {
    public VnProperties {
        if (standardIndex == null) standardIndex = "vn_products_standard";
        if (foldedIndex == null)   foldedIndex   = "vn_products_folded";
        if (icuIndex == null)      icuIndex      = "vn_products_icu";
        if (sampleSize <= 0) sampleSize = 5000;
    }
}
