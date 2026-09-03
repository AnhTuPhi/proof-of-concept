package com.poc;

import com.poc.ids.DiscordIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscordIdGeneratorTest {

    @Test
    void idsAreStrictlyIncreasing() {
        var gen = new DiscordIdGenerator(3, 7);
        long prev = -1;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "Discord ids must increase");
            prev = id;
        }
    }

    @Test
    void idsAreUnique() {
        var gen = new DiscordIdGenerator(0, 0);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            assertTrue(seen.add(gen.nextId()));
        }
    }

    @Test
    void decodesWorkerProcessAndTimestamp() {
        var gen = new DiscordIdGenerator(13, 21);
        Instant before = Instant.now();
        long id = gen.nextId();
        Instant after = Instant.now();

        assertEquals(13L, DiscordIdGenerator.workerOf(id));
        assertEquals(21L, DiscordIdGenerator.processOf(id));

        Instant ts = DiscordIdGenerator.timestampOf(id);
        assertFalse(ts.isBefore(before.minusMillis(1)));
        assertFalse(ts.isAfter(after.plusMillis(1)));
    }

    @Test
    void usesDiscordEpochNotUnix() {
        // A Discord ID minted "now" decodes to roughly now in wall-clock terms.
        // The internal timestamp field, however, is much smaller than a Unix ms
        // value because Discord's epoch is 2015-01-01, not 1970-01-01.
        var gen = new DiscordIdGenerator(0, 0);
        long id = gen.nextId();
        Instant decoded = DiscordIdGenerator.timestampOf(id);
        // Decoded wall-clock time must be after Discord's epoch.
        assertTrue(decoded.toEpochMilli() > DiscordIdGenerator.DISCORD_EPOCH);
    }

    @Test
    void rejectsOutOfRangeIds() {
        assertThrows(IllegalArgumentException.class, () -> new DiscordIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new DiscordIdGenerator(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new DiscordIdGenerator(-1, 0));
    }
}
