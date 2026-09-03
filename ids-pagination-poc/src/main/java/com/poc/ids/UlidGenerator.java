package com.poc.ids;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID (Universally Unique Lexicographically Sortable Identifier).
 *
 * 128-bit layout, Crockford base32 (26 chars):
 *   48 bits: timestamp (ms since unix epoch)
 *   80 bits: randomness
 *
 * Lexicographically sortable by time, URL-safe, case-insensitive.
 * Spec: https://github.com/ulid/spec
 */
public final class UlidGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIMESTAMP_LEN = 10;
    private static final int RANDOM_LEN = 16;

    private final SecureRandom random;

    private long lastTimestamp = -1L;
    private final byte[] lastRandom = new byte[10];

    public UlidGenerator() {
        this(new SecureRandom());
    }

    public UlidGenerator(SecureRandom random) {
        this.random = random;
    }

    public synchronized String nextUlid() {
        long now = System.currentTimeMillis();

        byte[] randomness = new byte[10];
        if (now == lastTimestamp) {
            incrementRandom(lastRandom);
            System.arraycopy(lastRandom, 0, randomness, 0, 10);
        } else {
            random.nextBytes(randomness);
            System.arraycopy(randomness, 0, lastRandom, 0, 10);
            lastTimestamp = now;
        }

        char[] out = new char[TIMESTAMP_LEN + RANDOM_LEN];
        encodeTimestamp(now, out, 0);
        encodeRandom(randomness, out, TIMESTAMP_LEN);
        return new String(out);
    }

    private static void encodeTimestamp(long ts, char[] out, int offset) {
        for (int i = TIMESTAMP_LEN - 1; i >= 0; i--) {
            int mod = (int) (ts & 0x1F);
            out[offset + i] = ENCODING[mod];
            ts >>>= 5;
        }
    }

    private static void encodeRandom(byte[] bytes, char[] out, int offset) {
        // 10 bytes → 16 base32 chars
        // 80 bits split into 16 groups of 5 bits
        out[offset]      = ENCODING[(bytes[0] & 0xF8) >>> 3];
        out[offset + 1]  = ENCODING[((bytes[0] & 0x07) << 2) | ((bytes[1] & 0xC0) >>> 6)];
        out[offset + 2]  = ENCODING[(bytes[1] & 0x3E) >>> 1];
        out[offset + 3]  = ENCODING[((bytes[1] & 0x01) << 4) | ((bytes[2] & 0xF0) >>> 4)];
        out[offset + 4]  = ENCODING[((bytes[2] & 0x0F) << 1) | ((bytes[3] & 0x80) >>> 7)];
        out[offset + 5]  = ENCODING[(bytes[3] & 0x7C) >>> 2];
        out[offset + 6]  = ENCODING[((bytes[3] & 0x03) << 3) | ((bytes[4] & 0xE0) >>> 5)];
        out[offset + 7]  = ENCODING[bytes[4] & 0x1F];
        out[offset + 8]  = ENCODING[(bytes[5] & 0xF8) >>> 3];
        out[offset + 9]  = ENCODING[((bytes[5] & 0x07) << 2) | ((bytes[6] & 0xC0) >>> 6)];
        out[offset + 10] = ENCODING[(bytes[6] & 0x3E) >>> 1];
        out[offset + 11] = ENCODING[((bytes[6] & 0x01) << 4) | ((bytes[7] & 0xF0) >>> 4)];
        out[offset + 12] = ENCODING[((bytes[7] & 0x0F) << 1) | ((bytes[8] & 0x80) >>> 7)];
        out[offset + 13] = ENCODING[(bytes[8] & 0x7C) >>> 2];
        out[offset + 14] = ENCODING[((bytes[8] & 0x03) << 3) | ((bytes[9] & 0xE0) >>> 5)];
        out[offset + 15] = ENCODING[bytes[9] & 0x1F];
    }

    private static void incrementRandom(byte[] r) {
        for (int i = r.length - 1; i >= 0; i--) {
            int v = (r[i] & 0xFF) + 1;
            r[i] = (byte) (v & 0xFF);
            if (v <= 0xFF) return;
        }
        throw new IllegalStateException("ULID randomness overflow within same ms");
    }

    public static Instant timestampOf(String ulid) {
        if (ulid == null || ulid.length() != 26) {
            throw new IllegalArgumentException("ULID must be 26 chars");
        }
        long ts = 0L;
        for (int i = 0; i < TIMESTAMP_LEN; i++) {
            int v = decodeChar(ulid.charAt(i));
            ts = (ts << 5) | v;
        }
        return Instant.ofEpochMilli(ts);
    }

    private static int decodeChar(char c) {
        for (int i = 0; i < ENCODING.length; i++) {
            if (ENCODING[i] == Character.toUpperCase(c)) return i;
        }
        throw new IllegalArgumentException("Invalid ULID char: " + c);
    }
}
