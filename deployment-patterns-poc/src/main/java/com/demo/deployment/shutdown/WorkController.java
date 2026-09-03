package com.demo.deployment.shutdown;

import com.demo.deployment.common.InstanceInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Slow endpoint used to demonstrate request draining at shutdown — start a
 * long /work request, then SIGTERM the process and watch it run to completion
 * while readiness reports REFUSING_TRAFFIC.
 */
@RestController
public class WorkController {

    private final InstanceInfo instance;
    private final InFlightRequestTracker tracker;

    public WorkController(InstanceInfo instance, InFlightRequestTracker tracker) {
        this.instance = instance;
        this.tracker = tracker;
    }

    @GetMapping("/work")
    public Map<String, Object> work(@RequestParam(defaultValue = "5000") long ms) throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(ms);
        return Map.of(
                "instance", instance.instanceId(),
                "color", instance.color(),
                "version", instance.version(),
                "tookMs", System.currentTimeMillis() - start,
                "inFlightAtFinish", tracker.current()
        );
    }

    @GetMapping("/inflight")
    public Map<String, Object> inflight() {
        return Map.of("instance", instance.instanceId(), "inFlight", tracker.current());
    }
}
