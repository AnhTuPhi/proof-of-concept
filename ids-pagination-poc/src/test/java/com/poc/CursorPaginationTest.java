package com.poc;

import com.poc.model.Item;
import com.poc.pagination.Cursor;
import com.poc.pagination.CursorPage;
import com.poc.pagination.CursorPaginator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CursorPaginationTest {

    @Test
    void cursorEncodeDecodeRoundTrip() {
        var original = new Cursor(Instant.parse("2026-05-30T10:00:00Z"), 12345L);
        String token = original.encode();
        Cursor decoded = Cursor.decode(token);
        assertEquals(original, decoded);
    }

    @Test
    void cursorDecodeRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Cursor.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Cursor.decode(null));
    }

    @Test
    void walksAllRowsExactlyOnceWithoutDuplicates() {
        List<Item> data = makeItems(53);
        var paginator = new CursorPaginator(data);
        int pageSize = 10;

        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            CursorPage<Item> page = paginator.pageFrom(cursor, pageSize);
            for (Item it : page.items()) {
                assertTrue(seen.add(it.id()), "duplicate id across pages: " + it.id());
            }
            pages++;
            if (!page.hasMore()) {
                assertNull(page.nextCursor(), "last page must have null cursor");
                break;
            }
            cursor = page.nextCursor();
            assertNotNull(cursor);
        }
        assertEquals(data.size(), seen.size());
        assertEquals(6, pages); // 53 / 10 → 6 pages
    }

    @Test
    void tiebreaksByIdWhenCreatedAtMatches() {
        // Three rows at the exact same instant — cursor must order by id.
        Instant t = Instant.parse("2026-05-30T00:00:00Z");
        List<Item> data = List.of(
            new Item(300, "c", t),
            new Item(100, "a", t),
            new Item(200, "b", t));

        var paginator = new CursorPaginator(data);
        CursorPage<Item> p1 = paginator.firstPage(2);
        assertEquals(List.of(100L, 200L),
            p1.items().stream().map(Item::id).toList());
        assertTrue(p1.hasMore());

        CursorPage<Item> p2 = paginator.pageFrom(p1.nextCursor(), 2);
        assertEquals(List.of(300L),
            p2.items().stream().map(Item::id).toList());
        assertFalse(p2.hasMore());
    }

    @Test
    void emptyDatasetReturnsEmptyPageWithNoCursor() {
        var paginator = new CursorPaginator(List.of());
        CursorPage<Item> page = paginator.firstPage(10);
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    void pageSizeMustBePositive() {
        var paginator = new CursorPaginator(makeItems(5));
        assertThrows(IllegalArgumentException.class, () -> paginator.firstPage(0));
        assertThrows(IllegalArgumentException.class, () -> paginator.firstPage(-1));
    }

    private static List<Item> makeItems(int n) {
        List<Item> out = new ArrayList<>(n);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            // Two rows per second forces tiebreaking by id in roughly half the steps.
            out.add(new Item(1000L + i, "row-" + i, base.plusSeconds(i / 2L)));
        }
        return out;
    }
}
