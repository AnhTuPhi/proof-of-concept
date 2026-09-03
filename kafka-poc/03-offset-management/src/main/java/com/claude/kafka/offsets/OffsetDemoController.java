package com.claude.kafka.offsets;

import com.claude.kafka.common.metrics.KafkaAppMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drive the demo from curl/Postman:
 * <pre>
 *   POST /offsets/start?mode=AUTO&crashAfter=50
 *   POST /offsets/start?mode=SYNC_AFTER
 *   POST /offsets/start?mode=IDEMPOTENT_AFTER
 *   POST /offsets/stop?mode=AUTO
 *   GET  /offsets/stats
 * </pre>
 * Compare "consumed" counts to messages actually produced — that's how
 * teams discover their pipeline isn't doing what they thought.
 */
@RestController
@RequestMapping("/offsets")
@RequiredArgsConstructor
public class OffsetDemoController {

    private final IdempotencyStore idempotency;
    private final KafkaAppMetrics metrics;
    private final Map<CommitMode, DemoConsumer> workers = new ConcurrentHashMap<>();

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrap;

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam CommitMode mode,
                                     @RequestParam(defaultValue = "0") int crashAfter) {
        workers.computeIfAbsent(mode, m -> {
            DemoConsumer c = new DemoConsumer(bootstrap, m, crashAfter, idempotency, metrics);
            Thread t = new Thread(c, "consumer-" + m);
            t.setDaemon(true);
            t.start();
            return c;
        });
        return Map.of("started", mode, "crashAfter", crashAfter);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam CommitMode mode) {
        DemoConsumer c = workers.remove(mode);
        if (c != null) c.stop();
        return Map.of("stopped", mode);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : workers.entrySet()) {
            out.put(e.getKey().name(), Map.of(
                    "consumed", e.getValue().getConsumed(),
                    "skippedDuplicates", e.getValue().getSkipped()
            ));
        }
        return out;
    }
}
