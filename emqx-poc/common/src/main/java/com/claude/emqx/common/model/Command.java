package com.claude.emqx.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Downlink command from backend to device.
 *
 * <p>Carries a {@code correlationId} so devices can reply on a response topic -
 * see POC 07 ({@code MQTT 5 request/response}) and POC 11 (device shadow desired
 * state acknowledgement).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Command(
        String commandId,
        String correlationId,    // echoed in the device's response message
        String action,           // e.g. "set_target_temp", "reboot", "shadow_desired"
        Map<String, Object> args,
        Instant issuedAt,
        Long expiresAtEpochMillis
) {}
