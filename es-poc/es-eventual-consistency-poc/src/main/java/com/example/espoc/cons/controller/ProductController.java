package com.example.espoc.cons.controller;

import com.example.espoc.cons.model.Product;
import com.example.espoc.cons.service.ProductService;
import com.example.espoc.cons.service.ProductService.ReadMode;
import com.example.espoc.cons.service.ProductService.WriteMode;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService svc;

    public ProductController(ProductService svc) { this.svc = svc; }

    @PostMapping
    public Map<String, Object> save(@RequestParam(defaultValue = "default") String mode, @RequestBody Product p) {
        WriteMode wm = switch (mode) {
            case "wait-for"      -> WriteMode.WAIT_FOR;
            case "force-refresh" -> WriteMode.FORCE_REFRESH;
            default              -> WriteMode.DEFAULT;
        };
        long t0 = System.nanoTime();
        Product saved = svc.save(p, wm);
        return Map.of("mode", wm.name(), "elapsedMs", (System.nanoTime() - t0) / 1_000_000, "product", saved);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, @RequestParam(defaultValue = "es-only") String mode) {
        Optional<Product> p = mode.equals("read-through")
                ? svc.getByIdReadThrough(id)
                : svc.getById(id);
        return Map.of("mode", mode, "found", p.isPresent(), "product", p.orElse(null));
    }

    /** Trigger the version-skew scenario in one call. */
    @PostMapping("/version-demo/{id}")
    public Map<String, Object> versionDemo(@PathVariable String id) {
        long now = Instant.now().toEpochMilli();
        // Write 3 versions out-of-order: middle, newest, oldest
        Product middle = new Product(id, "SKU-V", "Middle version", 50_00, Instant.ofEpochMilli(now - 1000));
        Product newest = new Product(id, "SKU-V", "Newest version", 75_00, Instant.ofEpochMilli(now));
        Product oldest = new Product(id, "SKU-V", "Oldest version", 25_00, Instant.ofEpochMilli(now - 2000));
        svc.forceWriteWithVersion(middle, middle.updatedAt().toEpochMilli());
        svc.forceWriteWithVersion(newest, newest.updatedAt().toEpochMilli());   // becomes the persisted one
        svc.forceWriteWithVersion(oldest, oldest.updatedAt().toEpochMilli());   // rejected as stale
        return Map.of("id", id, "expected", "Newest version", "current", svc.getById(id).orElse(null));
    }
}
