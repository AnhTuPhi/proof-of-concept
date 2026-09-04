package com.example.espoc.reindex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reindex")
public record ReindexProperties(String alias, String v1Index, String v2Index, int initialCount) {
    public ReindexProperties {
        if (alias == null) alias = "products";
        if (v1Index == null) v1Index = "products_v1";
        if (v2Index == null) v2Index = "products_v2";
        if (initialCount <= 0) initialCount = 50_000;
    }
}
