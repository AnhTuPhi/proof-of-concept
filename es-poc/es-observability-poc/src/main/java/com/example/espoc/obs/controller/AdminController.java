package com.example.espoc.obs.controller;

import com.example.espoc.obs.service.DiagnosticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DiagnosticsService svc;

    public AdminController(DiagnosticsService svc) { this.svc = svc; }

    @PostMapping("/slowlog")
    public Map<String, Object> enableSlowlog(@RequestParam(defaultValue = "1000") long queryMs,
                                             @RequestParam(defaultValue = "500")  long fetchMs) {
        return svc.enableSlowLog(queryMs, fetchMs);
    }

    @DeleteMapping("/slowlog")
    public Map<String, Object> disableSlowlog() { return svc.disableSlowLog(); }

    @GetMapping("/hot-threads")
    public Map<String, Object> hotThreads() { return svc.hotThreads(); }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam(defaultValue = "product") String q) { return svc.profile(q); }

    @GetMapping("/diagnose")
    public Map<String, Object> diagnose() { return svc.diagnose(); }
}
