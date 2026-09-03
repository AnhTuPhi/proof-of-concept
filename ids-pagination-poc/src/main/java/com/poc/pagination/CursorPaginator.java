package com.poc.pagination;

import com.poc.model.Item;

import java.util.Comparator;
import java.util.List;

/**
 * Composite-cursor paginator over an in-memory list of {@link Item}.
 *
 * Order key: (createdAt ASC, id ASC).
 *
 * Why this beats OFFSET / LIMIT:
 *   - OFFSET N forces the DB to scan and discard N rows on every page → O(N) per page.
 *   - Cursors use an index range scan: WHERE (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT k.
 *   - Pages are stable under concurrent inserts (no shifting offsets).
 *
 * Equivalent SQL for the "next page" query:
 *   SELECT * FROM items
 *    WHERE (created_at > :ts)
 *       OR (created_at = :ts AND id > :id)
 *    ORDER BY created_at ASC, id ASC
 *    LIMIT :pageSize;
 */
public final class CursorPaginator {

    private static final Comparator<Item> ORDER =
        Comparator.comparing(Item::createdAt).thenComparingLong(Item::id);

    private final List<Item> sortedItems;

    public CursorPaginator(List<Item> items) {
        this.sortedItems = items.stream().sorted(ORDER).toList();
    }

    public CursorPage<Item> firstPage(int pageSize) {
        return pageFrom(null, pageSize);
    }

    public CursorPage<Item> pageFrom(String cursorToken, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0");
        }

        Cursor cursor = cursorToken == null ? null : Cursor.decode(cursorToken);

        List<Item> filtered = sortedItems.stream()
            .filter(it -> cursor == null || isAfter(it, cursor))
            .limit(pageSize + 1L) // fetch one extra to know if more exist
            .toList();

        boolean hasMore = filtered.size() > pageSize;
        List<Item> page = hasMore ? filtered.subList(0, pageSize) : filtered;

        String next = null;
        if (hasMore && !page.isEmpty()) {
            Item last = page.get(page.size() - 1);
            next = new Cursor(last.createdAt(), last.id()).encode();
        }

        return new CursorPage<>(page, next, hasMore);
    }

    /** True when {@code it} sorts strictly after the cursor under (createdAt, id). */
    private static boolean isAfter(Item it, Cursor c) {
        int cmp = it.createdAt().compareTo(c.createdAt());
        if (cmp != 0) return cmp > 0;
        return it.id() > c.id();
    }
}
