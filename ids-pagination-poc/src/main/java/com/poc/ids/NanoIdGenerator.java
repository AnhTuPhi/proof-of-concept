package com.poc.ids;

import java.security.SecureRandom;

/**
 * NanoID — secure, URL-friendly, compact unique IDs.
 *
 * Default: 21 chars, alphabet of 64 URL-safe symbols.
 * Collision probability roughly equal to UUIDv4 at this size.
 * Spec: https://github.com/ai/nanoid
 *
 * NOT time-sortable — use Snowflake / ULID when you need ordering.
 */
public final class NanoIdGenerator {

    public static final char[] DEFAULT_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toCharArray();
    public static final int DEFAULT_SIZE = 21;

    private final SecureRandom random;
    private final char[] alphabet;
    private final int size;
    private final int mask;
    private final int step;

    public NanoIdGenerator() {
        this(new SecureRandom(), DEFAULT_ALPHABET, DEFAULT_SIZE);
    }

    public NanoIdGenerator(SecureRandom random, char[] alphabet, int size) {
        if (alphabet == null || alphabet.length == 0 || alphabet.length > 256) {
            throw new IllegalArgumentException("alphabet length must be 1..256");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        this.random = random;
        this.alphabet = alphabet.clone();
        this.size = size;
        // Compute the bit mask and step per nanoid reference impl.
        this.mask = (2 << (int) Math.floor(Math.log(alphabet.length - 1) / Math.log(2))) - 1;
        this.step = (int) Math.ceil(1.6 * mask * size / alphabet.length);
    }

    public String nextId() {
        StringBuilder id = new StringBuilder(size);
        byte[] bytes = new byte[step];
        while (true) {
            random.nextBytes(bytes);
            for (int i = 0; i < step; i++) {
                int idx = bytes[i] & mask;
                if (idx < alphabet.length) {
                    id.append(alphabet[idx]);
                    if (id.length() == size) {
                        return id.toString();
                    }
                }
            }
        }
    }
}
