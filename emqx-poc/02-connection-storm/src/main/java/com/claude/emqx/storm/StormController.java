package com.claude.emqx.storm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Drive the storm test:
 *
 *   1) {@code POST /storm/setup?count=20000} - establish steady-state fleet
 *   2) docker restart emqx1   <-- the trigger
 *   3) Watch reconnect distribution in Grafana; observe broker accept errors
 *      WITHOUT jitter, then enable jitter:
 *   4) {@code POST /storm/strategy?name=DECORRELATED_JITTER}
 *   5) restart broker again - storm should now flatten over ~30s
 */
@RestController
@RequestMapping("/storm")
public class StormController {

    private final StormFleetService svc;

    public StormController(StormFleetService svc) { this.svc = svc; }

    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestParam(defaultValue = "5000") int count) {
        svc.bootstrap(count);
        return Map.of("requested", count);
    }

    @PostMapping("/strategy")
    public Map<String, Object> strategy(@RequestParam ReconnectStrategy name) {
        svc.setStrategy(name);
        return Map.of("strategy", name);
    }

    @PostMapping("/disconnect-all")
    public Map<String, Object> disconnectAll() {
        // Simulates EMQX restart by force-disconnecting every client from this side.
        // Use this if you can't restart the broker (e.g. when running this in CI).
        int n = svc.forceDisconnectAll();
        return Map.of("disconnected", n);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() { return svc.snapshot(); }
}
