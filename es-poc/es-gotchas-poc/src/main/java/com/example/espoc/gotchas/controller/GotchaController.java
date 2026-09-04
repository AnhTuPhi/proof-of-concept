package com.example.espoc.gotchas.controller;

import com.example.espoc.gotchas.gotchas.Gotcha;
import com.example.espoc.gotchas.service.GotchaRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gotcha")
public class GotchaController {

    private final GotchaRegistry registry;

    public GotchaController(GotchaRegistry registry) { this.registry = registry; }

    @GetMapping
    public List<String> list() { return registry.list(); }

    @GetMapping("/{name}/explain")
    public Map<String, Object> explain(@PathVariable String name) {
        Gotcha g = registry.resolve(name);
        return Map.of("name", g.name(), "explanation", g.explain());
    }

    @PostMapping("/{name}/break")
    public Map<String, Object> doBreak(@PathVariable String name,
                                       @RequestParam Map<String, String> params) throws Exception {
        return registry.resolve(name).trigger(params);
    }

    @PostMapping("/{name}/fix")
    public Map<String, Object> doFix(@PathVariable String name,
                                     @RequestParam Map<String, String> params) throws Exception {
        return registry.resolve(name).fix(params);
    }
}
