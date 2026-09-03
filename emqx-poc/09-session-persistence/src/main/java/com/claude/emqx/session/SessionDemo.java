package com.claude.emqx.session;

import com.claude.emqx.common.client.MqttClientProperties;
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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates the four (clean-start × session-expiry) combinations.
 *
 * <p>The four cases:
 * <table>
 *   <tr><th>cleanStart</th><th>sessionExpiry</th><th>Effect</th></tr>
 *   <tr><td>true</td><td>0</td><td>Stateless. Common for backend services. POC 03 / 04 use this.</td></tr>
 *   <tr><td>true</td><td>>0</td><td>Start fresh; if you disconnect, broker holds your subs for N seconds. Use when you want a "rolling restart" device to not miss messages.</td></tr>
 *   <tr><td>false</td><td>0</td><td>Resume previous session if exists, then immediately expire. Quirky - rarely useful.</td></tr>
 *   <tr><td>false</td><td>>0</td><td>Classic persistent session. Broker queues QoS 1/2 messages for you while offline; you get them on reconnect.</td></tr>
 * </table>
 *
 * <p>The MQTT 3 "cleanSession=false with no expiry" is GONE in MQTT 5 - you MUST
 * set an expiry. This was deliberate: the #1 EMQX support ticket was "our broker
 * has 5M zombie sessions".
 */
@Service
public class SessionDemo {

    private static final Logger log = LoggerFactory.getLogger(SessionDemo.class);

    private final MqttClientProperties props;

    private final Map<String, AtomicInteger> receivedBy = new ConcurrentHashMap<>();
    private final Map<String, MqttAsyncClient> activeClients = new ConcurrentHashMap<>();

    public SessionDemo(MqttClientProperties props) { this.props = props; }

    /**
     * Connect with explicit cleanStart + sessionExpiry. Subscribes to test/{clientId}/data
     * with QoS 1 so any queued-while-offline messages will be delivered on reconnect.
     */
    public ConnectResult connect(String clientId, boolean cleanStart, long sessionExpirySec) throws MqttException {
        MqttAsyncClient client = new MqttAsyncClient(
                props.brokerUrl(), clientId, new MemoryPersistence());
        AtomicInteger counter = receivedBy.computeIfAbsent(clientId, k -> new AtomicInteger());

        client.setCallback(new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) {
                counter.incrementAndGet();
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });

        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setUserName(props.username());
        opts.setPassword(props.password().getBytes(StandardCharsets.UTF_8));
        opts.setCleanStart(cleanStart);
        opts.setSessionExpiryInterval(sessionExpirySec);
        opts.setKeepAliveInterval(30);

        IMqttToken token = client.connect(opts);
        token.waitForCompletion(10_000);
        // CONNACK has a sessionPresent flag - the killer feature for persistent sessions.
        // It tells us whether the broker found our previous session.
        boolean sessionPresent = token.getSessionPresent();

        // Subscribe AFTER connect. If session was present, the broker remembers our
        // subscriptions - so technically the subscribe call is idempotent.
        // We still call it because the test wants to ensure subscription exists.
        client.subscribe("test/" + clientId + "/data", 1).waitForCompletion();
        activeClients.put(clientId, client);

        log.info("connect clientId={} cleanStart={} sessionExpiry={}s sessionPresent={}",
                clientId, cleanStart, sessionExpirySec, sessionPresent);
        return new ConnectResult(clientId, cleanStart, sessionExpirySec, sessionPresent, counter.get());
    }

    /** Disconnect cleanly. With sessionExpiry>0, broker holds the session until expiry. */
    public void disconnect(String clientId) throws MqttException {
        MqttAsyncClient c = activeClients.remove(clientId);
        if (c != null) {
            c.disconnect().waitForCompletion();
            c.close();
        }
    }

    /**
     * Backend publishes to test/{clientId}/data while clientId is offline.
     * If the broker held a session, these get queued; the client sees them on reconnect.
     */
    public void publishToOfflineClient(String clientId, int count) throws MqttException {
        MqttAsyncClient pub = new MqttAsyncClient(props.brokerUrl(),
                "pub-" + System.nanoTime(), new MemoryPersistence());
        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setUserName(props.username());
        opts.setPassword(props.password().getBytes(StandardCharsets.UTF_8));
        opts.setCleanStart(true);
        opts.setSessionExpiryInterval(0);
        pub.connect(opts).waitForCompletion();
        for (int i = 0; i < count; i++) {
            MqttMessage m = new MqttMessage(("msg-" + i).getBytes(StandardCharsets.UTF_8));
            m.setQos(1);
            pub.publish("test/" + clientId + "/data", m);
        }
        pub.disconnect().waitForCompletion();
        pub.close();
    }

    public int receivedBy(String clientId) {
        AtomicInteger n = receivedBy.get(clientId);
        return n == null ? 0 : n.get();
    }

    public record ConnectResult(String clientId, boolean cleanStart, long sessionExpiryInterval,
                                boolean sessionPresent, int receivedSoFar) {}
}
