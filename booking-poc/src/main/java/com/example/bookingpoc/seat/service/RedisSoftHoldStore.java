package com.example.bookingpoc.seat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Cross-node soft-hold store. Activate by setting
 * {@code booking.seat.hold-store=redis} and providing a Redis connection
 * (Spring Boot auto-configures one from {@code spring.data.redis.*}).
 *
 * <p>Wire-level semantics:
 * <ul>
 *   <li><b>acquire</b> = {@code SET seat:hold:{id} customerId|token NX PX ttl} — atomic.</li>
 *   <li><b>peek</b>    = {@code GET} + {@code PTTL} for remaining lifetime.</li>
 *   <li><b>consume</b> = Lua compare-and-delete by full value, so a hold that
 *       has already expired and been re-acquired by another customer is
 *       never accidentally deleted.</li>
 * </ul>
 *
 * <p>The {@code customerId|token} delimiter assumes neither contains a
 * literal {@code '|'}; tokens are UUIDs which never do. Production should
 * upgrade to a Redis Hash with the token in a separate field so the Lua
 * compare matches only the token.
 */
@Component
@ConditionalOnProperty(name = "booking.seat.hold-store", havingValue = "redis")
public class RedisSoftHoldStore implements SoftHoldStore {

    private static final String KEY_PREFIX = "seat:hold:";
    private static final String DELIM = "|";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;
    private final Duration ttl;

    public RedisSoftHoldStore(StringRedisTemplate redis,
                              RedisScript<Long> softHoldReleaseScript,
                              @Value("${booking.seat.soft-hold-ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.releaseScript = softHoldReleaseScript;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public Optional<SoftHold> tryAcquire(Long seatId, String customerId) {
        String token = UUID.randomUUID().toString();
        String value = encode(customerId, token);
        Boolean ok = redis.opsForValue().setIfAbsent(key(seatId), value, ttl);
        if (!Boolean.TRUE.equals(ok)) {
            return Optional.empty();
        }
        return Optional.of(new SoftHold(seatId, customerId, token, Instant.now().plus(ttl)));
    }

    @Override
    public Optional<SoftHold> peek(Long seatId) {
        String key = key(seatId);
        String value = redis.opsForValue().get(key);
        if (value == null) return Optional.empty();
        int idx = value.indexOf(DELIM);
        if (idx <= 0) return Optional.empty();
        String customerId = value.substring(0, idx);
        String token = value.substring(idx + 1);
        Long pttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        Instant expiresAt = (pttl != null && pttl > 0)
                ? Instant.now().plusMillis(pttl)
                : Instant.now();
        return Optional.of(new SoftHold(seatId, customerId, token, expiresAt));
    }

    @Override
    public boolean consume(Long seatId, String token) {
        Optional<SoftHold> current = peek(seatId);
        if (current.isEmpty() || !current.get().token().equals(token)) {
            return false;
        }
        String expected = encode(current.get().customerId(), token);
        Long result = redis.execute(releaseScript, List.of(key(seatId)), expected);
        return result != null && result == 1L;
    }

    @Override
    public Duration getTtl() {
        return ttl;
    }

    private String key(Long seatId) {
        return KEY_PREFIX + seatId;
    }

    private String encode(String customerId, String token) {
        return customerId + DELIM + token;
    }
}
