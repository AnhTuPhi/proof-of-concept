package com.claude.emqx.sharedsub;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sharedsub")
public class SharedSubController {

    private final ConsumerGroup consumers;
    private final Producer producer;

    public SharedSubController(ConsumerGroup c, Producer p) { this.consumers = c; this.producer = p; }

    @PostMapping("/consumers")
    public List<String> startConsumers(
            @RequestParam(defaultValue = "ingest") String group,
            @RequestParam(defaultValue = "telemetry/+/data") String topic,
            @RequestParam(defaultValue = "4") int n,
            @RequestParam(defaultValue = "1") int qos) throws Exception {
        return consumers.startConsumers(group, topic, n, qos);
    }

    @PostMapping("/produce")
    public Map<String, Object> produce(
            @RequestParam(defaultValue = "telemetry/dev/data") String topic,
            @RequestParam(defaultValue = "1000") int count,
            @RequestParam(defaultValue = "1") int qos) throws Exception {
        producer.produce(topic, count, qos);
        return Map.of("published", count, "topic", topic);
    }

    @GetMapping("/distribution")
    public Map<String, Long> distribution() {
        return consumers.distributionSnapshot();
    }
}
