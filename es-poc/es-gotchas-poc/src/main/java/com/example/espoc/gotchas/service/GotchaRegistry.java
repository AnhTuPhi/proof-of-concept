package com.example.espoc.gotchas.service;

import com.example.espoc.common.web.ApiException;
import com.example.espoc.gotchas.gotchas.Gotcha;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GotchaRegistry {

    private final Map<String, Gotcha> byName = new LinkedHashMap<>();

    public GotchaRegistry(List<Gotcha> gotchas) {
        gotchas.stream().sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(g -> byName.put(g.name(), g));
    }

    public Gotcha resolve(String name) {
        Gotcha g = byName.get(name);
        if (g == null) throw ApiException.notFound("GOTCHA_NOT_FOUND", "Unknown gotcha: " + name);
        return g;
    }

    public List<String> list() { return List.copyOf(byName.keySet()); }
}
