package vn.com.dgo.poc.bloom;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api")
public class LookupController {

    private final UserLookupService service;
    private final UserRepository repository;

    public LookupController(UserLookupService service, UserRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/users/no-bloom/{email}")
    public ResponseEntity<User> noBloom(@PathVariable String email) {
        return service.findWithoutBloom(email)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/users/with-bloom/{email}")
    public ResponseEntity<User> withBloom(@PathVariable String email) {
        return service.findWithBloom(email)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/seed")
    @Transactional
    public Map<String, Object> seed(@RequestParam(defaultValue = "10000") int count) {
        repository.deleteAllInBatch();
        for (long i = 1; i <= count; i++) {
            repository.save(new User(i, "user" + i + "@dgo.local", "User " + i));
        }
        service.rebuildFromDb();
        return Map.of("seeded", count, "bloomSize", service.snapshot().bloomElementCount());
    }

    /**
     * Bench mô phỏng cache penetration: phần lớn email không tồn tại trong DB.
     * - missRatio = 0.95 nghĩa là 95% truy vấn là email không tồn tại.
     * - So sánh số DB hits giữa with-bloom và no-bloom sau khi chạy.
     */
    @PostMapping("/bench")
    public Map<String, Object> bench(
            @RequestParam(defaultValue = "5000") int iterations,
            @RequestParam(defaultValue = "0.95") double missRatio) {
        long maxExistingId = Math.max(2, repository.count());

        service.resetCounters();
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            service.findWithBloom(randomEmail(missRatio, maxExistingId));
        }
        long withMs = (System.nanoTime() - t0) / 1_000_000;
        var withSnap = service.snapshot();

        service.resetCounters();
        long t1 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            service.findWithoutBloom(randomEmail(missRatio, maxExistingId));
        }
        long noMs = (System.nanoTime() - t1) / 1_000_000;
        var noSnap = service.snapshot();

        return Map.of(
                "iterations", iterations,
                "missRatio", missRatio,
                "withBloom", Map.of(
                        "elapsedMs", withMs,
                        "dbHits", withSnap.dbHitsWithBloom(),
                        "bloomRejects", withSnap.bloomRejects(),
                        "falsePositives", withSnap.falsePositives()),
                "withoutBloom", Map.of(
                        "elapsedMs", noMs,
                        "dbHits", noSnap.dbHitsNoBloom()));
    }

    private static String randomEmail(double missRatio, long maxExistingId) {
        var rnd = ThreadLocalRandom.current();
        return rnd.nextDouble() < missRatio
                ? "ghost" + rnd.nextLong() + "@dgo.local"
                : "user" + rnd.nextLong(1, maxExistingId) + "@dgo.local";
    }

    @GetMapping("/stats")
    public UserLookupService.Stats stats() {
        return service.snapshot();
    }
}
