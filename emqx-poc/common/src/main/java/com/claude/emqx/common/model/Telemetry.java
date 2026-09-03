package com.claude.emqx.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Generic telemetry envelope used by simulated devices across the POCs.
 *
 * <p>Why one envelope:
 * <ul>
 *   <li>Realistic - most IoT shops standardize on a payload schema.</li>
 *   <li>Makes the rule-engine POC (06) simpler: one SELECT, one sink.</li>
 *   <li>The {@code tenantId} field demonstrates the multi-tenant pattern from POC 05.</li>
 * </ul>
 *
 * @param deviceId  globally unique device identifier
 * @param tenantId  multi-tenant namespace; must match the topic prefix in POC 05 ACLs
 * @param sequence  monotonic per-device counter (for gap detection)
 * @param metrics   arbitrary numeric metrics (e.g. temperature, rpm, voltage)
 * @param firmware  device firmware version - used by OTA POC (13)
 * @param ts        device-side timestamp (RFC 3339)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Telemetry(
        String deviceId,
        String tenantId,
        long sequence,
        Map<String, Double> metrics,
        String firmware,
        Instant ts
) {
    public static Telemetry sample(String deviceId, long sequence) {
        return new Telemetry(
                deviceId,
                "tenant-a",
                sequence,
                Map.of(
                        "temp_c", 18 + Math.random() * 12,
                        "humidity", 30 + Math.random() * 40,
                        "battery_pct", 60 + Math.random() * 40
                ),
                "1.0.0",
                Instant.now()
        );
    }
}
