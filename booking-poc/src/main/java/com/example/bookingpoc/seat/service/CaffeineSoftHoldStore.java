package com.example.bookingpoc.seat.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default soft-hold store: in-process Caffeine with {@code expireAfterWrite}.
 * {@code asMap().putIfAbsent} gives the same atomic check-and-set semantics
 * as Redis SETNX but only within a single JVM. For multi-replica deployments
 * use {@link RedisSoftHoldStore} instead.
 */
@Component
@ConditionalOnProperty(name = "booking.seat.hold-store", havingValue = "memory", matchIfMissing = true)
public class CaffeineSoftHoldStore implements SoftHoldStore {

    private final Cache<Long, SoftHold> cache;
    private final Duration ttl;

    public CaffeineSoftHoldStore(@Value("${booking.seat.soft-hold-ttl-seconds:300}") long ttlSeconds) {
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(100_000)
                .build();
    }

    @Override
    public Optional<SoftHold> tryAcquire(Long seatId, String customerId) {
        String token = UUID.randomUUID().toString();
        SoftHold candidate = new SoftHold(seatId, customerId, token, Instant.now().plus(ttl));
        SoftHold existing = cache.asMap().putIfAbsent(seatId, candidate);
        return existing == null ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public Optional<SoftHold> peek(Long seatId) {
        return Optional.ofNullable(cache.getIfPresent(seatId));
    }

    @Override
    public boolean consume(Long seatId, String token) {
        AtomicBoolean removed = new AtomicBoolean(false);
        cache.asMap().computeIfPresent(seatId, (k, hold) -> {
            if (hold.token().equals(token)) {
                removed.set(true);
                return null;
            }
            return hold;
        });
        return removed.get();
    }

    @Override
    public Duration getTtl() {
        return ttl;
    }
}
