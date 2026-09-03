package com.poc;

import com.poc.ids.UlidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UlidGeneratorTest {

    @Test
    void ulidLengthAndAlphabet() {
        var gen = new UlidGenerator();
        for (int i = 0; i < 1_000; i++) {
            String ulid = gen.nextUlid();
            assertEquals(26, ulid.length());
            for (char c : ulid.toCharArray()) {
                assertTrue("0123456789ABCDEFGHJKMNPQRSTVWXYZ".indexOf(c) >= 0,
                    "invalid Crockford char: " + c);
            }
        }
    }

    @Test
    void ulidsAreUniqueAndIncreasing() {
        var gen = new UlidGenerator();
        Set<String> seen = new HashSet<>();
        String prev = "";
        for (int i = 0; i < 10_000; i++) {
            String ulid = gen.nextUlid();
            assertTrue(seen.add(ulid), "duplicate ULID");
            assertTrue(ulid.compareTo(prev) > 0, "ULIDs must be monotonic");
            prev = ulid;
        }
    }

    @Test
    void timestampRoundTrip() {
        var gen = new UlidGenerator();
        Instant before = Instant.now();
        String ulid = gen.nextUlid();
        Instant after = Instant.now();

        Instant ts = UlidGenerator.timestampOf(ulid);
        assertFalse(ts.isBefore(before.minusMillis(1)));
        assertFalse(ts.isAfter(after.plusMillis(1)));
        assertTrue(Duration.between(ts, after).toSeconds() < 2);
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> UlidGenerator.timestampOf("too-short"));
        assertThrows(IllegalArgumentException.class, () -> UlidGenerator.timestampOf(null));
    }
}
