package vn.com.ipas.poc.inventory;

import java.time.Instant;

public record Reservation(
        String id,
        String userId,
        String sku,
        int qty,
        Instant expiresAt,
        Status status
) {
    public enum Status { ACTIVE, CONFIRMED, RELEASED, EXPIRED }

    public Reservation withStatus(Status s) {
        return new Reservation(id, userId, sku, qty, expiresAt, s);
    }

    public Reservation withExpiry(Instant newExpiry) {
        return new Reservation(id, userId, sku, qty, newExpiry, status);
    }

    public boolean isExpired(Instant now) {
        return status == Status.ACTIVE && now.isAfter(expiresAt);
    }
}
