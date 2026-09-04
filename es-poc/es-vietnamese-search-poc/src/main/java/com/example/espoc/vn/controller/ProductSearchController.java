package com.example.espoc.vn.controller;

import com.example.espoc.vn.service.CompareSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductSearchController {

    private final CompareSearchService svc;

    public ProductSearchController(CompareSearchService svc) { this.svc = svc; }

    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String q) { return svc.compare(q); }
}
