package com.example.espoc.pagination.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pagination")
public record PaginationProperties(
        String indexName,
        int initialLoadCount,
        int bulkChunkSize,
        boolean autoLoad
) {
    public PaginationProperties {
        if (indexName == null || indexName.isBlank()) indexName = "pag_products";
        if (initialLoadCount <= 0) initialLoadCount = 1_000_000;
        if (bulkChunkSize <= 0) bulkChunkSize = 5_000;
    }
}
