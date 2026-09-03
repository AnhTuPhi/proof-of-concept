package com.poc.pagination;

import java.util.List;

/**
 * One page of results plus the cursor needed to fetch the next page.
 * {@code nextCursor} is null when there are no more rows.
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
