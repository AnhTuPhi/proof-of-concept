package com.demo.deployment.canary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class RouterController {

    private final TrafficRouter router;

    public RouterController(TrafficRouter router) {
        this.router = router;
    }

    /**
     * Simulated user-facing endpoint. The router decides which backend would
     * have served the request; the response embeds that backend's identity
     * so demo scripts can count BLUE vs GREEN hits.
     */
    @GetMapping("/api/hello")
    public Map<String, Object> hello(@RequestParam String userId) {
        Backend b = router.route(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("servedBy", b.color());
        out.put("version", b.version());
        out.put("greeting", greet(b));
        return out;
    }

    private static String greet(Backend b) {
        return b == Backend.GREEN ? "hello from the NEW (v2) checkout flow"
                                  : "hello from the legacy (v1) flow";
    }

    @GetMapping("/router/config")
    public Map<String, Object> view() {
        TrafficRouter.Config c = router.config();
        return Map.of(
                "mode", c.mode(),
                "activeColor", c.activeColor(),
                "canaryWeight", c.canaryWeight(),
                "hits", Map.of("blue", router.blueHits(), "green", router.greenHits())
        );
    }

    @PostMapping("/router/mode")
    public TrafficRouter.Config setMode(@RequestParam RoutingMode mode) {
        return router.setMode(mode);
    }

    @PostMapping("/router/active-color")
    public TrafficRouter.Config setActive(@RequestParam String color) {
        return router.setActiveColor(color);
    }

    @PostMapping("/router/canary-weight")
    public TrafficRouter.Config setWeight(@RequestParam int weight) {
        return router.setCanaryWeight(weight);
    }

    @PostMapping("/router/reset-counters")
    public Map<String, Object> reset() {
        router.resetCounters();
        return Map.of("ok", true);
    }
}
