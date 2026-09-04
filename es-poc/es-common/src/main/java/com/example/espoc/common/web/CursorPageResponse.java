package com.example.espoc.common.web;

import java.util.List;

/**
 * Envelope for cursor-based pagination (search_after / PIT).
 * {@code nextCursor} is opaque and round-trippable: clients echo it back to continue.
 * Null means no more pages.
 */
public record CursorPageResponse<T>(
        List<T> items,
        String nextCursor,
        int size,
        long tookMillis
) {
    public static <T> CursorPageResponse<T> of(List<T> items, String cursor, int size, long tookMs) {
        return new CursorPageResponse<>(items, cursor, size, tookMs);
    }
}
