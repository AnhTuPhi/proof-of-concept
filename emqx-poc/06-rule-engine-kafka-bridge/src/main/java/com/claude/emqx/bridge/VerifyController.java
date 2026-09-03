package com.claude.emqx.bridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/verify")
public class VerifyController {

    private final TelemetryKafkaConsumer kafka;
    private final JdbcTemplate jdbc;

    public VerifyController(TelemetryKafkaConsumer kafka, JdbcTemplate jdbc) {
        this.kafka = kafka; this.jdbc = jdbc;
    }

    @GetMapping("/kafka-count")
    public Map<String, Object> kafkaCount() {
        return Map.of("consumedFromIotTelemetry", kafka.count());
    }

    @GetMapping("/pg-count")
    public Map<String, Object> pgCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM telemetry", Long.class);
        Long last = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM MAX(ts))::bigint FROM telemetry", Long.class);
        return Map.of("rowsInTelemetry", n == null ? 0 : n, "lastTsEpoch", last == null ? 0 : last);
    }
}
