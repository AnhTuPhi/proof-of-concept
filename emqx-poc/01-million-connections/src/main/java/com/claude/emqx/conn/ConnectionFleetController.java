package com.claude.emqx.conn;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Drive-test controller for POC 01.
 *
 * <p>Typical script (also in scripts/poc-01-million-conns.sh):
 * <pre>
 *   curl -XPOST 'localhost:8101/fleet/start?count=50000&rate=5000'
 *   curl -XPOST 'localhost:8101/fleet/traffic?rate=200'
 *   # ...watch grafana...
 *   curl 'localhost:8101/fleet/size'
 * </pre>
 */
@RestController
@RequestMapping("/fleet")
public class ConnectionFleetController {

    private final ConnectionFleetService svc;

    public ConnectionFleetController(ConnectionFleetService svc) { this.svc = svc; }

    @PostMapping("/start")
    public CompletableFuture<ConnectionFleetService.FleetResult> start(
            @RequestParam(defaultValue = "10000") int count,
            @RequestParam(defaultValue = "1000") int rate) {
        return svc.startFleet(count, rate);
    }

    @PostMapping("/traffic")
    public Map<String, Object> traffic(@RequestParam(defaultValue = "100") int rate) {
        svc.startTrickleTraffic(rate);
        return Map.of("publishesPerSecond", rate, "currentClients", svc.size());
    }

    @GetMapping("/size")
    public Map<String, Object> size() {
        return Map.of("connected", svc.size());
    }
}
