package vn.com.poc.disruptor.util;

/**
 * FNV-1a 64-bit hash. Not cryptographic — just cheap enough to run on every
 * event, on the hot path, to catch accidental corruption (bit flips, a
 * mis-decoded field, a torn write) between "producer built the event" and
 * "integrity stage inspected it".
 */
public final class Checksums {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Checksums() {
    }

    public static long fnv1a64(long a, int b, String c, int d, long priceBits, long qty, char side) {
        long hash = FNV_OFFSET_BASIS;
        hash = mix(hash, a);
        hash = mix(hash, b);
        hash = mixString(hash, c);
        hash = mix(hash, d);
        hash = mix(hash, priceBits);
        hash = mix(hash, qty);
        hash = mix(hash, side);
        return hash;
    }

    private static long mix(long hash, long value) {
        for (int i = 0; i < 8; i++) {
            byte b = (byte) (value >>> (i * 8));
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static long mixString(long hash, String s) {
        if (s == null) {
            return mix(hash, -1L);
        }
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
