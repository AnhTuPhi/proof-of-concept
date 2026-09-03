package com.claude.emqx.cluster;

import com.claude.emqx.common.util.Json;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One MQTT subscriber per cluster node.
 *
 * <p>The probe pattern: subscribe N clients, one to each node directly (NOT via
 * HAProxy), to the same topic. Publish from one client. Count receipts on the
 * others. If a node is partitioned away from the rest, its subscriber sees
 * nothing while the others receive normally — that's split-brain visible from
 * the client side.
 *
 * <p>Why bypass HAProxy: HAProxy load-balances connections at TCP level. To
 * probe each node we need a direct TCP connection to that node. In production
 * you'd never connect this way, but for diagnostics, EMQX exposes per-node
 * listeners.
 */
@Service
public class ClusterProbe {

    private static final Logger log = LoggerFactory.getLogger(ClusterProbe.class);

    @Value("${cluster.nodes:tcp://localhost:1883,tcp://localhost:1884,tcp://localhost:1885}")
    private String nodesCsv;

    @Value("${cluster.mqtt-username:backend-svc}")
    private String username;
    @Value("${cluster.mqtt-password:backend-secret}")
    private String password;

    public static final String PROBE_TOPIC = "$cluster/probe";

    private final Map<String, NodeProbe> probes = new LinkedHashMap<>();

    @PostConstruct
    void start() throws MqttException {
        for (String url : nodesCsv.split(",")) {
            String node = url.trim();
            NodeProbe p = new NodeProbe(node, username, password);
            p.connect();
            probes.put(node, p);
        }
        log.info("cluster probe attached to {} nodes", probes.size());
    }

    public ProbeResult probe() throws MqttException, InterruptedException {
        String corrId = UUID.randomUUID().toString();
        // pick an arbitrary node to publish from
        NodeProbe publisher = probes.values().iterator().next();
        publisher.publish(corrId);
        // wait briefly for fan-out
        Thread.sleep(500);
        ProbeResult r = new ProbeResult(corrId);
        for (var e : probes.entrySet()) {
            r.received.put(e.getKey(), e.getValue().received(corrId));
        }
        return r;
    }

    public Map<String, Boolean> nodeStatus() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        probes.forEach((k, v) -> out.put(k, v.connected()));
        return out;
    }

    public static class NodeProbe {
        private final String url, user, pass;
        private MqttAsyncClient client;
        private final Map<String, Integer> seenCounts = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger msgsIn = new AtomicInteger(0);

        NodeProbe(String url, String user, String pass) { this.url = url; this.user = user; this.pass = pass; }

        void connect() throws MqttException {
            client = new MqttAsyncClient(url, "probe-" + UUID.randomUUID(), new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override public void messageArrived(String topic, MqttMessage msg) {
                    msgsIn.incrementAndGet();
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = Json.fromBytes(msg.getPayload(), Map.class);
                        String corr = (String) m.get("corr");
                        seenCounts.merge(corr, 1, Integer::sum);
                    } catch (Exception e) { /* ignore */ }
                }
                @Override public void disconnected(MqttDisconnectResponse r) { log.warn("probe {} disconnected: {}", url, r.getReasonString()); }
                @Override public void mqttErrorOccurred(MqttException ex) {}
                @Override public void deliveryComplete(IMqttToken t) {}
                @Override public void connectComplete(boolean reconnect, String uri) { log.info("probe {} connect-complete reconnect={}", url, reconnect); }
                @Override public void authPacketArrived(int rc, MqttProperties p) {}
            });
            MqttConnectionOptions opts = new MqttConnectionOptions();
            opts.setUserName(user);
            opts.setPassword(pass.getBytes(StandardCharsets.UTF_8));
            opts.setCleanStart(true);
            opts.setSessionExpiryInterval(0L);
            opts.setKeepAliveInterval(10);
            opts.setAutomaticReconnect(true);
            opts.setConnectionTimeout(5);
            client.connect(opts).waitForCompletion(5000);
            client.subscribe(PROBE_TOPIC, 1).waitForCompletion();
        }

        void publish(String corrId) throws MqttException {
            MqttMessage m = new MqttMessage(Json.toBytes(Map.of(
                    "corr", corrId,
                    "from", url,
                    "at",   System.currentTimeMillis())));
            m.setQos(1);
            client.publish(PROBE_TOPIC, m);
        }

        int received(String corrId) { return seenCounts.getOrDefault(corrId, 0); }
        boolean connected() { return client != null && client.isConnected(); }
    }

    public record ProbeResult(String corrId, Map<String, Integer> received) {
        public ProbeResult(String corrId) { this(corrId, new LinkedHashMap<>()); }
        public List<String> partitioned() {
            return received.entrySet().stream()
                    .filter(e -> e.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }
}
