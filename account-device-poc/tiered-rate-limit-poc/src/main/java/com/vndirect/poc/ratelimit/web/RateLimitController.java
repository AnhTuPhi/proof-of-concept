package com.vndirect.poc.ratelimit.web;

import com.vndirect.poc.ratelimit.domain.Tier;
import com.vndirect.poc.ratelimit.service.RateLimitDecision;
import com.vndirect.poc.ratelimit.service.RateLimitService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ratelimit")
@CrossOrigin
public class RateLimitController {

    private final RateLimitService service;

    public RateLimitController(RateLimitService service) { this.service = service; }

    @GetMapping("/users")
    public List<Map<String, Object>> users() { return service.userSummary(); }

    @PostMapping("/tier")
    public Map<String, Object> setTier(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        Tier tier = Tier.valueOf(body.get("tier"));
        service.setTier(userId, tier);
        return Map.of("userId", userId, "tier", tier.name(), "status", "ok");
    }

    @PostMapping("/call")
    public ResponseEntity<?> call(@RequestBody Map<String, String> body) {
        String userId = body.getOrDefault("userId", "anonymous");
        String ip = body.getOrDefault("ip", "127.0.0.1");
        String endpoint = body.getOrDefault("endpoint", "/api/quote");
        RateLimitDecision d = service.check(userId, ip, endpoint);
        if (d.allowed()) {
            return ResponseEntity.ok().header("X-RateLimit-User-Remaining",
                String.valueOf(d.dimensions().get(0).remaining())).body(Map.of(
                "allowed", true,
                "decision", d,
                "endpoint", endpoint,
                "responseBody", "ok: " + endpoint + " for " + userId
            ));
        }
        return ResponseEntity.status(429)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, d.retryAfterMillis() / 1000)))
            .body(Map.of(
                "allowed", false,
                "decision", d,
                "retryAfterSeconds", Math.max(1, d.retryAfterMillis() / 1000),
                "error", "rate limit exceeded on " + d.violatedDimension()
            ));
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        service.reset();
        return Map.of("status", "reset");
    }
}
