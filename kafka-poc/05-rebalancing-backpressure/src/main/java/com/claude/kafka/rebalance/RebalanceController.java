package com.claude.kafka.rebalance;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/rebalance")
@RequiredArgsConstructor
public class RebalanceController {

    private final BackpressureConsumer consumer;

    /**
     * Inject artificial latency per record. With {@code delayMs >= 50} you can
     * watch in-flight climb past the high water mark and see PAUSED in the
     * logs; lower it again to see RESUMED.
     */
    @PostMapping("/delay")
    public Map<String, Object> setDelay(@RequestParam int ms) {
        consumer.setDelayMs(ms);
        return Map.of("delayMs", ms);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("inFlight", consumer.getInFlight());
    }
}
