package com.poc;

import com.poc.ids.NanoIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NanoIdGeneratorTest {

    @Test
    void defaultLengthIs21AndAlphabetIsUrlSafe() {
        var gen = new NanoIdGenerator();
        String allowed = new String(NanoIdGenerator.DEFAULT_ALPHABET);
        for (int i = 0; i < 1_000; i++) {
            String id = gen.nextId();
            assertEquals(21, id.length());
            for (char c : id.toCharArray()) {
                assertTrue(allowed.indexOf(c) >= 0, "char outside alphabet: " + c);
            }
        }
    }

    @Test
    void idsAreUnique() {
        var gen = new NanoIdGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            assertTrue(seen.add(gen.nextId()), "duplicate nanoid");
        }
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class,
            () -> new NanoIdGenerator(new java.security.SecureRandom(), new char[]{}, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new NanoIdGenerator(new java.security.SecureRandom(),
                NanoIdGenerator.DEFAULT_ALPHABET, 0));
    }
}
