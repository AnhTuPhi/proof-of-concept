package com.claude.emqx.conn;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.metrics.MqttMetrics;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds a fleet of HiveMQ MQTT 5 clients, each masquerading as one IoT device.
 *
 * <p>HiveMQ client chosen for this POC specifically because it uses one Netty
 * event-loop group across <em>all</em> clients in the JVM. The shared executor
 * is wired here so we can demonstrate the difference between "1 thread per
 * client" (Paho) - which dies around 5k connections - and "1 thread per core"
 * (HiveMQ) - which scales to 6 digits with the right OS tuning.
 *
 * <p>Each client connects with {@code cleanStart=true} and {@code sessionExpiry=0}
 * because we want the broker to forget us instantly on disconnect. For persistent
 * session tests, see POC 09.
 */
@Service
public class ConnectionFleetService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionFleetService.class);

    private final MqttClientProperties props;
    private final MqttMetrics metrics;
    private final ScheduledExecutorService keepBusyExec;

    /** clientId -> client. ConcurrentHashMap survives ~1M entries; we never iterate hot. */
    private final Map<String, Mqtt5AsyncClient> fleet = new ConcurrentHashMap<>();
    private final AtomicInteger publishSequence = new AtomicInteger();

    public ConnectionFleetService(MqttClientProperties props, MeterRegistry reg) {
        this.props = props;
        this.metrics = new MqttMetrics(reg, "01-million-conns");
        // One scheduler thread is enough to publish at modest rates from all clients.
        // Don't be tempted to make this a per-client scheduler - that defeats the
        // whole point.
        this.keepBusyExec = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }

    /**
     * Spin up {@code count} clients concurrently with a small inter-connect delay
     * so we don't recreate the connection-storm problem (POC 02) here.
     *
     * @param count          total clients to add
     * @param connectsPerSec rate cap; the broker accepts ~10k/s with default tuning
     * @return future completing when ALL clients are connected (or attempted)
     */
    public CompletableFuture<FleetResult> startFleet(int count, int connectsPerSec) {
        int existing = fleet.size();
        log.info("Starting fleet: requesting {} clients ({} existing), rate {}/s", count, existing, connectsPerSec);

        CompletableFuture<FleetResult> done = new CompletableFuture<>();
        long delayBetweenMicros = 1_000_000L / Math.max(1, connectsPerSec);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();

        // We submit tasks at a steady cadence rather than firing them all at once.
        // ScheduledExecutorService with fixed-rate is intentionally pacing this.
        Runnable submitter = () -> {
            int idx = started.getAndIncrement();
            if (idx >= count) return;
            connectOne(existing + idx)
                    .thenAccept(success -> {
                        if (success) ok.incrementAndGet(); else fail.incrementAndGet();
                        if (ok.get() + fail.get() == count) {
                            done.complete(new FleetResult(ok.get(), fail.get(), fleet.size()));
                        }
                    });
        };

        keepBusyExec.scheduleAtFixedRate(submitter, 0, delayBetweenMicros, TimeUnit.MICROSECONDS);
        return done;
    }

    private CompletableFuture<Boolean> connectOne(int idx) {
        String clientId = props.clientIdPrefix() + "-" + idx + "-" + UUID.randomUUID().toString().substring(0, 8);

        Mqtt5AsyncClient client = MqttClient.builder()
                .useMqttVersion5()
                .identifier(clientId)
                .serverHost(extractHost(props.brokerUrl()))
                .serverPort(extractPort(props.brokerUrl(), 1883))
                // ESSENTIAL for scale: HiveMQ shares one Netty IO group across all
                // builders that don't override it. Explicitly setting executor=null
                // means "join the default shared one".
                .buildAsync();

        metrics.connectAttempts.increment();
        return client.connectWith()
                .cleanStart(true)
                .sessionExpiryInterval(0)
                .keepAlive(props.keepAliveSeconds())
                .send()
                .handle((Mqtt5ConnAck ack, Throwable err) -> {
                    if (err != null) {
                        metrics.connectFailure.increment();
                        if (err instanceof Mqtt5ConnAckException ce) {
                            log.warn("Connect denied [{}] reason={}", clientId, ce.getMqttMessage().getReasonCode());
                        } else if (idx % 1000 == 0) {
                            // Don't log every failure or we'll log-bomb at 100k clients
                            log.warn("Connect failed [{}] {}", clientId, err.toString());
                        }
                        return false;
                    }
                    metrics.connectSuccess.increment();
                    metrics.activeConnections.incrementAndGet();
                    fleet.put(clientId, client);

                    // Subscribe to a per-device command topic and arrange the client
                    // to receive broadcast messages. Subscribing 100k clients
                    // simultaneously is a topic-tree stress test - see POC 14.
                    client.subscribeWith()
                            .topicFilter("device/" + clientId + "/cmd")
                            .qos(MqttQos.AT_LEAST_ONCE)
                            .callback(pub -> metrics.receiveCount.increment())
                            .send();
                    return true;
                });
    }

    /**
     * Drain a fixed rate of publishes from random fleet members. This is what
     * proves the connections are <i>useful</i>, not just idle TCP.
     */
    public void startTrickleTraffic(int publishesPerSecond) {
        long periodMicros = 1_000_000L / publishesPerSecond;
        keepBusyExec.scheduleAtFixedRate(() -> {
            if (fleet.isEmpty()) return;
            // pick a pseudo-random client without iterating the whole map
            int n = publishSequence.incrementAndGet();
            var anyClient = fleet.values().stream().skip(n % fleet.size()).findFirst().orElse(null);
            if (anyClient == null) return;

            String payload = "{\"seq\":" + n + ",\"ts\":" + System.currentTimeMillis() + "}";
            long start = System.nanoTime();
            anyClient.publishWith()
                    .topic("device/" + anyClient.getConfig().getClientIdentifier().orElseThrow() + "/telemetry")
                    .payload(payload.getBytes(StandardCharsets.UTF_8))
                    .qos(MqttQos.AT_MOST_ONCE)   // QoS 0 - this is a scale test, not a durability test
                    .send()
                    .whenComplete((r, e) -> {
                        if (e == null) {
                            metrics.publishCount.increment();
                            metrics.publishBytes.increment(payload.length());
                            metrics.publishLatency.record(Duration.ofNanos(System.nanoTime() - start));
                        }
                    });
        }, 0, periodMicros, TimeUnit.MICROSECONDS);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Disconnecting {} clients", fleet.size());
        // Disconnect all in parallel. We don't await each; the JVM is exiting.
        fleet.values().forEach(c -> c.disconnect().exceptionally(t -> null));
        keepBusyExec.shutdownNow();
    }

    public int size() { return fleet.size(); }

    private static String extractHost(String url) {
        // tcp://host:port or ssl://host:port
        int slash = url.indexOf("//");
        int colon = url.indexOf(':', slash + 2);
        return url.substring(slash + 2, colon < 0 ? url.length() : colon);
    }

    private static int extractPort(String url, int def) {
        int slash = url.indexOf("//");
        int colon = url.indexOf(':', slash + 2);
        if (colon < 0) return def;
        return Integer.parseInt(url.substring(colon + 1));
    }

    public record FleetResult(int connected, int failed, int totalNow) {}
}
