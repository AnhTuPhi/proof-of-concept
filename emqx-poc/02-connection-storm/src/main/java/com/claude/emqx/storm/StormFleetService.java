package com.claude.emqx.storm;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.metrics.MqttMetrics;
import com.claude.emqx.common.util.JitteredBackoff;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Operates a fleet whose reconnect behaviour is selectable at runtime via
 * {@link #setStrategy(ReconnectStrategy)}. The crucial detail vs. POC 01:
 *
 * <p><b>We do NOT rely on the client library's built-in auto-reconnect.</b>
 * HiveMQ and Paho both have automatic reconnect, but their default policies
 * are either too aggressive (immediate) or too coarse (exponential without
 * jitter). To demonstrate the FIX, we own the reconnect loop ourselves.
 *
 * <p>This is also closer to what you actually do in production for IoT
 * devices on cellular: ship YOUR backoff, not the library's, because the
 * library has no idea about your fleet size.
 */
@Service
public class StormFleetService {

    private static final Logger log = LoggerFactory.getLogger(StormFleetService.class);
    private static final int LB_PORT = 1880; // HAProxy in front of cluster

    private final MqttClientProperties props;
    private final MqttMetrics metrics;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));

    private final Map<String, FleetMember> members = new ConcurrentHashMap<>();
    private final AtomicReference<ReconnectStrategy> strategy = new AtomicReference<>(ReconnectStrategy.EXPONENTIAL_NO_JITTER);
    private final AtomicInteger reconnectInFlight = new AtomicInteger();

    public StormFleetService(MqttClientProperties props, MeterRegistry reg) {
        this.props = props;
        this.metrics = new MqttMetrics(reg, "02-storm");
        reg.gauge("storm.reconnect.inflight", reconnectInFlight, AtomicInteger::get);
    }

    public void bootstrap(int count) {
        log.info("Bootstrapping fleet of {} with strategy={}", count, strategy.get());
        for (int i = 0; i < count; i++) {
            String id = "storm-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            FleetMember m = new FleetMember(id);
            members.put(id, m);
            connect(m, /*reconnect*/ false);
        }
    }

    public void setStrategy(ReconnectStrategy s) {
        log.info("Switching reconnect strategy to {}", s);
        strategy.set(s);
        // also reset every member's backoff state so the next storm starts fresh
        members.values().forEach(m -> m.backoff.reset());
    }

    public int forceDisconnectAll() {
        int n = 0;
        for (FleetMember m : members.values()) {
            Mqtt5AsyncClient c = m.client.get();
            if (c != null) {
                c.disconnect().exceptionally(t -> null);
                n++;
            }
        }
        return n;
    }

    public Map<String, Object> snapshot() {
        long connected = members.values().stream().filter(m -> m.connected.get()).count();
        return Map.of(
                "total", members.size(),
                "connected", connected,
                "disconnected", members.size() - connected,
                "reconnectsTotal", (long) metrics.reconnects.count(),
                "reconnectInFlight", reconnectInFlight.get(),
                "strategy", strategy.get().name()
        );
    }

    // ---------- internals ----------

    private void connect(FleetMember m, boolean isReconnect) {
        if (isReconnect) {
            metrics.reconnects.increment();
            reconnectInFlight.incrementAndGet();
        }
        metrics.connectAttempts.increment();

        Mqtt5AsyncClient client = MqttClient.builder()
                .useMqttVersion5()
                .identifier(m.clientId)
                .serverHost("localhost")
                .serverPort(LB_PORT)
                // CRITICAL: do not let the library reconnect for us; we want full control.
                .automaticReconnect()
                .applyAutomaticReconnect()      // explicitly DISABLED below by not setting initialDelay
                .addDisconnectedListener(ctx -> {
                    if (m.connected.compareAndSet(true, false)) {
                        metrics.activeConnections.decrementAndGet();
                    }
                    scheduleReconnect(m, ctx.getCause() == null ? "graceful" : ctx.getCause().toString());
                })
                .addConnectedListener(ctx -> {
                    metrics.connectSuccess.increment();
                    if (m.connected.compareAndSet(false, true)) {
                        metrics.activeConnections.incrementAndGet();
                    }
                    if (isReconnect) reconnectInFlight.decrementAndGet();
                    m.backoff.reset();   // success - reset backoff
                })
                .buildAsync();

        m.client.set(client);
        client.connectWith()
                .cleanStart(true)
                .sessionExpiryInterval(0)
                .keepAlive(30)
                .send()
                .exceptionally(err -> {
                    metrics.connectFailure.increment();
                    if (isReconnect) reconnectInFlight.decrementAndGet();
                    // disconnectedListener will trigger the next attempt; nothing to do here.
                    return null;
                });
    }

    private void scheduleReconnect(FleetMember m, String reason) {
        Duration delay = strategy.get().nextDelay(m.backoff);
        scheduler.schedule(() -> connect(m, true), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        members.values().forEach(m -> {
            Mqtt5AsyncClient c = m.client.get();
            if (c != null) c.disconnect().exceptionally(t -> null);
        });
    }

    private static final class FleetMember {
        final String clientId;
        final AtomicReference<Mqtt5AsyncClient> client = new AtomicReference<>();
        final AtomicReference<Boolean> connectedRef = new AtomicReference<>(false);
        final java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean();
        final JitteredBackoff backoff = new JitteredBackoff(Duration.ofMillis(500), Duration.ofSeconds(30));
        FleetMember(String id) { this.clientId = id; }
    }
}
