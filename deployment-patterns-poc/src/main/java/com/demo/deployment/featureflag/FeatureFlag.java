package com.demo.deployment.featureflag;

/**
 * A flag with:
 *   - enabled: master kill-switch (off => always false, regardless of rollout)
 *   - rolloutPercent: 0..100, percentage of users seeing the flag ON
 *
 * Bucketing is deterministic on userId so the same user always gets the same
 * answer across requests (sticky rollout). Match-by-hash, not coin-flip.
 */
public record FeatureFlag(String key, boolean enabled, int rolloutPercent) {
    public FeatureFlag {
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("rolloutPercent must be 0..100, got " + rolloutPercent);
        }
    }

    public boolean isOnFor(String userId) {
        if (!enabled) return false;
        if (rolloutPercent >= 100) return true;
        if (rolloutPercent <= 0) return false;
        int bucket = Math.floorMod((key + ":" + userId).hashCode(), 100);
        return bucket < rolloutPercent;
    }
}
