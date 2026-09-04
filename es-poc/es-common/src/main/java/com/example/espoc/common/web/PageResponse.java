package com.example.espoc.common.web;

import java.util.List;

/** Uniform envelope for offset-based pagination responses. */
public record PageResponse<T>(
        List<T> items,
        long totalHits,
        int page,
        int size,
        long tookMillis
) {
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size, long tookMs) {
        return new PageResponse<>(items, total, page, size, tookMs);
    }
}
