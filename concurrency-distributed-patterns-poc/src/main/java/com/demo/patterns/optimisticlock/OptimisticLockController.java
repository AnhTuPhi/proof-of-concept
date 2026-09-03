package com.demo.patterns.optimisticlock;

import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/demo/optimistic")
public class OptimisticLockController {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockController.class);

    private final ProductService service;

    public OptimisticLockController(ProductService service) {
        this.service = service;
    }

    @PostMapping("/products")
    public Map<String, Object> create(@RequestParam String name,
                                      @RequestParam int stock) {
        Product p = service.create(name, stock);
        return view(p);
    }

    @GetMapping("/products/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return view(service.get(id));
    }

    @PostMapping("/products/{id}/decrement")
    public ResponseEntity<?> decrement(@PathVariable Long id,
                                       @RequestParam(defaultValue = "1") int amount) {
        try {
            return ResponseEntity.ok(view(service.decrement(id, amount)));
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "OptimisticLockConflict",
                    "message", "Another transaction modified this product first; retry"
            ));
        }
    }

    /**
     * Spin up {@code threads} concurrent decrements against the same product.
     * In a well-behaved store, total successes should equal initial stock and
     * conflicts should account for the rest. With NO @Version, two threads
     * could each read stock=10, both write stock=9 — losing one decrement.
     */
    @PostMapping("/products/{id}/concurrent-decrement")
    public Map<String, Object> concurrent(@PathVariable Long id,
                                          @RequestParam(defaultValue = "10") int threads,
                                          @RequestParam(defaultValue = "1") int amount) throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.decrement(id, amount);
                    successes.incrementAndGet();
                    return "ok";
                } catch (OptimisticLockingFailureException | OptimisticLockException e) {
                    conflicts.incrementAndGet();
                    return "conflict";
                } catch (RuntimeException e) {
                    other.incrementAndGet();
                    return "error:" + e.getClass().getSimpleName();
                }
            }));
        }
        start.countDown();
        for (Future<String> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        Product after = service.get(id);
        Map<String, Object> result = new HashMap<>();
        result.put("threads", threads);
        result.put("successes", successes.get());
        result.put("conflicts", conflicts.get());
        result.put("otherErrors", other.get());
        result.put("finalStock", after.getStock());
        result.put("finalVersion", after.getVersion());
        log.info("Concurrent decrement result: {}", result);
        return result;
    }

    private static Map<String, Object> view(Product p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("stock", p.getStock());
        m.put("version", p.getVersion());
        return m;
    }
}
