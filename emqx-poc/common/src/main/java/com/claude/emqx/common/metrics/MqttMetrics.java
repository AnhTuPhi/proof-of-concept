package com.claude.emqx.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standard Micrometer wiring shared by every POC.
 *
 * <p>Each app exposes:
 * <ul>
 *   <li>{@code mqtt.connect.attempts} / {@code mqtt.connect.success} / {@code mqtt.connect.failure}</li>
 *   <li>{@code mqtt.publish.latency} - timer; the dimension we care about for QoS comparisons (POC 03)</li>
 *   <li>{@code mqtt.publish.bytes}   - counter (POC 04 shared subscription throughput)</li>
 *   <li>{@code mqtt.connections.active} - gauge; how many TCP sockets this app currently holds</li>
 *   <li>{@code mqtt.reconnects} - counter; tracked separately from initial connects (POC 02)</li>
 * </ul>
 */
public final class MqttMetrics {

    public final Counter connectAttempts;
    public final Counter connectSuccess;
    public final Counter connectFailure;
    public final Counter reconnects;
    public final Counter publishCount;
    public final Counter publishBytes;
    public final Timer publishLatency;
    public final Counter receiveCount;
    public final AtomicInteger activeConnections = new AtomicInteger();
    public final AtomicLong lastSequenceGap = new AtomicLong();

    public MqttMetrics(MeterRegistry reg, String pocName) {
        this.connectAttempts = Counter.builder("mqtt.connect.attempts").tag("poc", pocName).register(reg);
        this.connectSuccess  = Counter.builder("mqtt.connect.success").tag("poc", pocName).register(reg);
        this.connectFailure  = Counter.builder("mqtt.connect.failure").tag("poc", pocName).register(reg);
        this.reconnects      = Counter.builder("mqtt.reconnects").tag("poc", pocName).register(reg);
        this.publishCount    = Counter.builder("mqtt.publish.count").tag("poc", pocName).register(reg);
        this.publishBytes    = Counter.builder("mqtt.publish.bytes").tag("poc", pocName).register(reg);
        this.publishLatency  = Timer.builder("mqtt.publish.latency").tag("poc", pocName).register(reg);
        this.receiveCount    = Counter.builder("mqtt.receive.count").tag("poc", pocName).register(reg);
        reg.gauge("mqtt.connections.active", java.util.List.of(io.micrometer.core.instrument.Tag.of("poc", pocName)),
                activeConnections, AtomicInteger::get);
        reg.gauge("mqtt.sequence.gap.last", java.util.List.of(io.micrometer.core.instrument.Tag.of("poc", pocName)),
                lastSequenceGap, AtomicLong::get);
    }
}
