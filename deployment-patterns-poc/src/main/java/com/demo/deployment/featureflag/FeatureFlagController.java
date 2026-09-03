package com.demo.deployment.featureflag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

@RestController
public class FeatureFlagController {

    private final FeatureFlagService flags;

    public FeatureFlagController(FeatureFlagService flags) {
        this.flags = flags;
    }

    @GetMapping("/flags")
    public Collection<FeatureFlag> list() {
        return flags.all();
    }

    @GetMapping("/flags/{key}/evaluate")
    public Map<String, Object> evaluate(@PathVariable String key, @RequestParam String userId) {
        return Map.of("key", key, "userId", userId, "enabled", flags.isEnabled(key, userId));
    }

    @PostMapping("/flags/{key}")
    public FeatureFlag update(@PathVariable String key,
                              @RequestParam boolean enabled,
                              @RequestParam(defaultValue = "100") int rolloutPercent) {
        return flags.upsert(key, enabled, rolloutPercent);
    }

    /**
     * Realistic consumer of a flag: the checkout endpoint serves either the
     * legacy or the new implementation depending on the rollout decision.
     */
    @GetMapping("/checkout")
    public Map<String, Object> checkout(@RequestParam String userId) {
        boolean newCheckout = flags.isEnabled("new-checkout", userId);
        return Map.of(
                "userId", userId,
                "implementation", newCheckout ? "v2-new-checkout" : "v1-legacy-checkout",
                "flag", "new-checkout"
        );
    }
}
