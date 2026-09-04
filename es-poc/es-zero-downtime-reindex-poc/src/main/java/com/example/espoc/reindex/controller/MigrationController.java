package com.example.espoc.reindex.controller;

import com.example.espoc.reindex.service.MigrationService;
import com.example.espoc.reindex.service.MigrationState;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/migration")
public class MigrationController {

    private final MigrationService svc;
    private final MigrationState state;

    public MigrationController(MigrationService svc, MigrationState state) {
        this.svc = svc;
        this.state = state;
    }

    @PostMapping("/start")
    public Map<String, String> start() {
        svc.start();
        return Map.of("phase", state.phase().name());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", state.phase().name());
        m.put("phaseEnteredAt", state.phaseEnteredAt().toString());
        m.put("dualWriteActive", state.isDualWriteActive());
        m.put("reindexTaskId", state.reindexTaskId());
        m.put("reindexCreated", state.lastReindexCreated());
        m.put("reindexUpdated", state.lastReindexUpdated());
        m.put("lastError", state.lastError());
        return m;
    }

    @PostMapping("/swap")
    public Map<String, String> swap() throws IOException {
        svc.swap();
        return Map.of("phase", state.phase().name());
    }

    @PostMapping("/complete")
    public Map<String, String> complete() {
        svc.complete();
        return Map.of("phase", state.phase().name());
    }

    @PostMapping("/rollback")
    public Map<String, String> rollback() throws IOException {
        svc.rollback();
        return Map.of("phase", state.phase().name());
    }

    @DeleteMapping("/v1")
    public Map<String, String> deleteV1() throws IOException {
        svc.deleteV1();
        return Map.of("status", "deleted");
    }
}
