package com.example.bookingpoc.seat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Single-key check-and-set with TTL for soft seat holds.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link CaffeineSoftHoldStore} — in-process; default. Single JVM only.
 *   <li>{@link RedisSoftHoldStore} — cross-node; activate with
 *       {@code booking.seat.hold-store=redis} for multi-pod / multi-VM deployments.
 * </ul>
 *
 * <p>The contract is the same as Redis {@code SET key value NX PX ttl}:
 * exactly one concurrent caller wins {@link #tryAcquire}; losers see
 * {@code Optional.empty()}. Consume is compare-and-delete by token —
 * implementations MUST be atomic so a TTL-expired key isn't accidentally
 * deleted from a subsequent owner.
 */
public interface SoftHoldStore {

    record SoftHold(Long seatId, String customerId, String token, Instant expiresAt) {}

    Optional<SoftHold> tryAcquire(Long seatId, String customerId);

    Optional<SoftHold> peek(Long seatId);

    boolean consume(Long seatId, String token);

    Duration getTtl();
}
