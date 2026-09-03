package com.demo.deployment.featureflag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory feature flag store — stand-in for Unleash / LaunchDarkly.
 *
 * The whole point: the code paths for `new-checkout` are deployed in v2,
 * but the flag stays OFF (or at 1% canary) until the team is ready to release.
 * Deploy and release are independent.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final ConcurrentHashMap<String, FeatureFlag> flags = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        flags.put("new-checkout", new FeatureFlag("new-checkout", true, 0));
        flags.put("dark-mode", new FeatureFlag("dark-mode", true, 100));
        flags.put("ai-recommendations", new FeatureFlag("ai-recommendations", false, 0));
    }

    public boolean isEnabled(String key, String userId) {
        FeatureFlag f = flags.get(key);
        return f != null && f.isOnFor(userId);
    }

    public FeatureFlag get(String key) {
        return flags.get(key);
    }

    public Collection<FeatureFlag> all() {
        return flags.values();
    }

    public FeatureFlag upsert(String key, boolean enabled, int rolloutPercent) {
        FeatureFlag updated = new FeatureFlag(key, enabled, rolloutPercent);
        flags.put(key, updated);
        log.info("flag updated: {}", updated);
        return updated;
    }
}
