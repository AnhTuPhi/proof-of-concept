package com.demo.deployment.health;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Toggle endpoints so demo scripts can flip dependency state and watch the
 * probe groups react (liveness stays UP, readiness drops).
 */
@RestController
@RequestMapping("/admin/deps")
public class HealthAdminController {

    private final DbHealthIndicator db;
    private final DownstreamHealthIndicator downstream;

    public HealthAdminController(DbHealthIndicator db, DownstreamHealthIndicator downstream) {
        this.db = db;
        this.downstream = downstream;
    }

    @PostMapping("/db")
    public Map<String, Object> setDb(@RequestParam boolean up) {
        db.setUp(up);
        return Map.of("dep", "db", "up", up);
    }

    @PostMapping("/downstream")
    public Map<String, Object> setDownstream(@RequestParam boolean up) {
        downstream.setUp(up);
        return Map.of("dep", "downstream", "up", up);
    }
}
