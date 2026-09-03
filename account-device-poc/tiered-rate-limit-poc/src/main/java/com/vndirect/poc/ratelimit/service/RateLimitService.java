package com.vndirect.poc.ratelimit.service;

import com.vndirect.poc.ratelimit.domain.Tier;
import com.vndirect.poc.ratelimit.domain.TokenBucket;
import com.vndirect.poc.ratelimit.service.RateLimitDecision.DimensionState;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three independent buckets per request — user, IP, endpoint. Any of them tripping is a 429.
 * Keeping them separate matters because:
 *   - one buggy IP shouldn't get the user's whole quota burned by one endpoint
 *   - one hot endpoint shouldn't lock the user out of other endpoints
 *   - one user shouldn't soak the whole IP pool (NAT'd corporate networks)
 */
@Service
public class RateLimitService {

    private final Map<String, UserTier> userTier = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> endpointBuckets = new ConcurrentHashMap<>();

    public RateLimitService() {
        userTier.put("free-user", new UserTier(Tier.FREE));
        userTier.put("pro-user", new UserTier(Tier.PRO));
        userTier.put("enterprise-user", new UserTier(Tier.ENTERPRISE));
    }

    public void setTier(String userId, Tier tier) {
        userTier.put(userId, new UserTier(tier));
        userBuckets.remove(userId);
    }

    public RateLimitDecision check(String userId, String ip, String endpoint) {
        Tier tier = Optional.ofNullable(userTier.get(userId))
            .map(UserTier::tier)
            .orElse(Tier.FREE);
        TokenBucket userBucket = userBuckets.computeIfAbsent(userId, k -> new TokenBucket(tier));
        TokenBucket ipBucket = ipBuckets.computeIfAbsent(ip, k -> new TokenBucket(Tier.PRO));        // per-IP cap
        TokenBucket epBucket = endpointBuckets.computeIfAbsent(endpoint, k -> new TokenBucket(Tier.ENTERPRISE)); // soft-cap

        TokenBucket.ConsumeResult userRes = userBucket.tryConsume(1);
        TokenBucket.ConsumeResult ipRes = ipBucket.tryConsume(1);
        TokenBucket.ConsumeResult epRes = epBucket.tryConsume(1);

        String violated = null;
        long retry = 0;
        if (!userRes.allowed()) { violated = "user:" + userId; retry = Math.max(retry, userRes.retryAfterMillis()); }
        if (!ipRes.allowed())   { violated = violated == null ? "ip:" + ip : violated; retry = Math.max(retry, ipRes.retryAfterMillis()); }
        if (!epRes.allowed())   { violated = violated == null ? "endpoint:" + endpoint : violated; retry = Math.max(retry, epRes.retryAfterMillis()); }
        boolean allowed = violated == null;

        List<DimensionState> dims = List.of(
            new DimensionState("user", userId, tier.name(), userBucket.snapshot().remaining(), userBucket.snapshot().capacity()),
            new DimensionState("ip", ip, "PRO", ipBucket.snapshot().remaining(), ipBucket.snapshot().capacity()),
            new DimensionState("endpoint", endpoint, "ENTERPRISE", epBucket.snapshot().remaining(), epBucket.snapshot().capacity())
        );
        return new RateLimitDecision(allowed, retry, violated, dims);
    }

    public List<Map<String, Object>> userSummary() {
        return userTier.entrySet().stream().map(e -> {
            TokenBucket b = userBuckets.get(e.getKey());
            long remaining = b == null ? e.getValue().tier().capacity() : b.snapshot().remaining();
            return Map.<String, Object>of(
                "userId", e.getKey(),
                "tier", e.getValue().tier().name(),
                "capacity", e.getValue().tier().capacity(),
                "remaining", remaining
            );
        }).toList();
    }

    public void reset() {
        userBuckets.clear();
        ipBuckets.clear();
        endpointBuckets.clear();
    }

    private record UserTier(Tier tier) {}
}
