package com.poc.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Composite cursor: (createdAt, id).
 *
 * Encoded as URL-safe base64 of "{epochMillis}:{id}".
 * Opaque to clients — they pass it back unmodified for the next page.
 */
public record Cursor(Instant createdAt, long id) {

    public String encode() {
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("cursor must not be empty");
        }
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        int sep = raw.indexOf(':');
        if (sep < 0) {
            throw new IllegalArgumentException("malformed cursor: " + token);
        }
        long ts = Long.parseLong(raw.substring(0, sep));
        long id = Long.parseLong(raw.substring(sep + 1));
        return new Cursor(Instant.ofEpochMilli(ts), id);
    }
}
