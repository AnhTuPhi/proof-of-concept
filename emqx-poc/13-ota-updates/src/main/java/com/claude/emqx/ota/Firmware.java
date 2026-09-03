package com.claude.emqx.ota;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * One firmware image, server-side.
 *
 * <p>{@code bytes} stays in memory — this is a POC. Real campaigns serve from S3
 * or a CDN with EMQX only carrying the chunk metadata. Even at 1MB images, a
 * 1M-device rollout means 1TB of MQTT bytes; you do NOT want the broker on that
 * path.
 */
public record Firmware(
        String version,
        String targetClass,    // e.g. "thermostat-v2"
        byte[] bytes,
        String sha256,
        int chunkSize) {

    public int totalChunks() {
        return (bytes.length + chunkSize - 1) / chunkSize;
    }

    public byte[] chunk(int index) {
        int from = index * chunkSize;
        int to   = Math.min(from + chunkSize, bytes.length);
        return Arrays.copyOfRange(bytes, from, to);
    }

    public static String hash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Firmware of(String version, String targetClass, byte[] bytes, int chunkSize) {
        return new Firmware(version, targetClass, bytes, hash(bytes), chunkSize);
    }
}
