package com.claude.emqx.qos;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/qos")
public class QosController {
    private final QosBenchmark bench;
    public QosController(QosBenchmark bench) { this.bench = bench; }

    @GetMapping("/benchmark")
    public Map<String, QosBenchmark.Result> benchmark(
            @RequestParam(defaultValue = "10000") int count,
            @RequestParam(defaultValue = "256") int payload) throws Exception {
        return bench.compareAll(count, payload);
    }

    @GetMapping("/run")
    public QosBenchmark.Result run(
            @RequestParam int qos,
            @RequestParam(defaultValue = "10000") int count,
            @RequestParam(defaultValue = "256") int payload) throws Exception {
        return bench.run(qos, count, payload);
    }
}
