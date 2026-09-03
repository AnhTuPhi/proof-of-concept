package com.claude.emqx.retained;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/retained")
public class RetainedController {

    private final RetainedDemo demo;
    public RetainedController(RetainedDemo demo) { this.demo = demo; }

    @PostMapping("/set")
    public Map<String, Object> set(@RequestParam String topic,
                                   @RequestParam String payload,
                                   @RequestParam(defaultValue = "0") long ttl) throws Exception {
        demo.setRetained(topic, payload, ttl);
        return Map.of("topic", topic, "ttl", ttl);
    }

    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestParam String topic) throws Exception {
        demo.clearRetained(topic);
        return Map.of("topic", topic, "cleared", true);
    }

    @PostMapping("/spam")
    public Map<String, Object> spam(@RequestParam(defaultValue = "config/device") String prefix,
                                    @RequestParam(defaultValue = "1000") int n) throws Exception {
        demo.spamRetained(prefix, n);
        return Map.of("prefix", prefix, "count", n,
                "warning", "Check EMQX dashboard - retainer table size. Without TTL these never expire.");
    }
}
