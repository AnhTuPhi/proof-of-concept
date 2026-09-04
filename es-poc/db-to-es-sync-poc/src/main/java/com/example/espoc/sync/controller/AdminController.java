package com.example.espoc.sync.controller;

import com.example.espoc.sync.es.ProductEsIndexer;
import com.example.espoc.sync.repository.CdcProductRepository;
import com.example.espoc.sync.repository.NaiveProductRepository;
import com.example.espoc.sync.repository.OutboxEventRepository;
import com.example.espoc.sync.repository.OutboxProductRepository;
import com.example.espoc.sync.strategy.cdc.DebeziumEngineRunner;
import com.example.espoc.sync.strategy.outbox.OutboxPoller;
import com.example.espoc.sync.support.FailureInjector;
import com.example.espoc.sync.support.FailureInjector.Target;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics + scenario knobs for the demo. Not safe for production — these endpoints
 * intentionally let callers cause failures.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final NaiveProductRepository naiveRepo;
    private final OutboxProductRepository outboxRepo;
    private final CdcProductRepository cdcRepo;
    private final OutboxEventRepository outboxEventRepo;
    private final ProductEsIndexer indexer;
    private final OutboxPoller poller;
    private final ObjectProvider<DebeziumEngineRunner> cdcEngine;
    private final FailureInjector failures;

    public AdminController(NaiveProductRepository naiveRepo,
                           OutboxProductRepository outboxRepo,
                           CdcProductRepository cdcRepo,
                           OutboxEventRepository outboxEventRepo,
                           ProductEsIndexer indexer,
                           OutboxPoller poller,
                           ObjectProvider<DebeziumEngineRunner> cdcEngine,
                           FailureInjector failures) {
        this.naiveRepo = naiveRepo;
        this.outboxRepo = outboxRepo;
        this.cdcRepo = cdcRepo;
        this.outboxEventRepo = outboxEventRepo;
        this.indexer = indexer;
        this.poller = poller;
        this.cdcEngine = cdcEngine;
        this.failures = failures;
    }

    @GetMapping("/db-vs-es")
    public Map<String, Object> dbVsEs(@RequestParam(defaultValue = "all") String strategy) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (strategy.equals("naive")  || strategy.equals("all")) out.put("naive",  countPair(naiveRepo.count(),  indexer.count(ProductEsIndexer.IDX_NAIVE)));
        if (strategy.equals("outbox") || strategy.equals("all")) out.put("outbox", countPair(outboxRepo.count(), indexer.count(ProductEsIndexer.IDX_OUTBOX)));
        if (strategy.equals("cdc")    || strategy.equals("all")) out.put("cdc",    countPair(cdcRepo.count(),    indexer.count(ProductEsIndexer.IDX_CDC)));
        return out;
    }

    private Map<String, Object> countPair(long db, long es) {
        return Map.of("dbCount", db, "esCount", es, "drift", db - es);
    }

    @GetMapping("/outbox/stats")
    public Map<String, Object> outboxStats() {
        long pending = outboxEventRepo.countByPublishedAtIsNull();
        Instant oldest = outboxEventRepo.oldestPendingCreatedAt();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", pending);
        m.put("oldestPendingAgeMs", oldest == null ? 0 : Duration.between(oldest, Instant.now()).toMillis());
        m.put("pollerPaused", poller.isPaused());
        return m;
    }

    @PostMapping("/outbox/poller/pause")  public Map<String, String> pause()  { poller.pause();  return Map.of("status", "paused"); }
    @PostMapping("/outbox/poller/resume") public Map<String, String> resume() { poller.resume(); return Map.of("status", "resumed"); }

    @GetMapping("/cdc/offset")
    public Map<String, Object> cdcOffset() {
        DebeziumEngineRunner r = cdcEngine.getIfAvailable();
        if (r == null) return Map.of("enabled", false);
        return Map.of("enabled", true, "eventsProcessed", r.eventsProcessed(), "lastLsn", r.lastLsn());
    }

    @PostMapping("/inject/{target}")
    public Map<String, Object> inject(@PathVariable String target, @RequestParam(defaultValue = "1") int count) {
        Target t = Target.valueOf(target.toUpperCase());
        failures.inject(t, count);
        return Map.of("target", t.name(), "remaining", failures.remaining(t));
    }

    @PostMapping("/inject/clear")
    public Map<String, String> clear() { failures.clear(); return Map.of("status", "cleared"); }
}
