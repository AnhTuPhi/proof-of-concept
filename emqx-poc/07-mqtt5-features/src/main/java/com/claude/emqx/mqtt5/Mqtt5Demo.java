package com.claude.emqx.mqtt5;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Map;

/**
 * Showcases the four MQTT 5 features that change architecture decisions:
 *
 * <ol>
 *   <li><b>Reason codes</b> - explicit "why we disconnected/rejected" instead of "connection closed".
 *       In v3 you had to guess. In v5 the server-side disconnect packet carries a reason code
 *       (e.g. {@code 0x97 QuotaExceeded}) and an optional human-readable reason string.</li>
 *
 *   <li><b>User properties</b> - arbitrary key/value pairs in PUBLISH / CONNECT.
 *       Like Kafka headers. Use for tenant_id, correlation_id, trace_id, schema version.
 *       Eliminates the need to stuff metadata into the payload.</li>
 *
 *   <li><b>Topic alias</b> - send the full topic name once; subsequent publishes use a
 *       2-byte alias. Massive bandwidth save for long topics (e.g. Sparkplug B has
 *       16+ levels). Cap at {@code maxTopicAlias=65535}.</li>
 *
 *   <li><b>Request / response</b> - publish carries a {@code responseTopic} + {@code correlationData}.
 *       The MQTT-native way to do RPC over MQTT. Each side gets clean correlation
 *       without inventing schemes.</li>
 * </ol>
 *
 * <p>EMQX honours all four out of the box.
 */
@Service
public class Mqtt5Demo {

    private static final Logger log = LoggerFactory.getLogger(Mqtt5Demo.class);

    private final PahoMqtt5ClientFactory factory;
    private MqttAsyncClient backend;     // the "server side" - listens on cmd topic, replies
    private MqttAsyncClient device;      // the "device side" - sends requests, listens for replies

    private final Map<String, CompletableFuture<String>> awaitingReplies = new ConcurrentHashMap<>();
    private final List<String> reasonHistory = new ArrayList<>();

    public Mqtt5Demo(MqttClientProperties props) {
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    @PostConstruct
    void start() throws MqttException {
        backend = factory.build("backend-" + UUID.randomUUID().toString().substring(0, 6), new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) throws Exception {
                // Backend receives a request and replies on responseTopic
                MqttProperties p = msg.getProperties();
                String responseTopic = p.getResponseTopic();
                byte[] correlation = p.getCorrelationData();
                if (responseTopic == null) {
                    log.warn("ignoring request with no responseTopic: {}", topic);
                    return;
                }
                MqttMessage reply = new MqttMessage(("pong: " + new String(msg.getPayload(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                reply.setQos(1);
                MqttProperties rp = new MqttProperties();
                rp.setCorrelationData(correlation);
                // Echo user properties is good practice for tracing.
                rp.setUserProperties(p.getUserProperties());
                reply.setProperties(rp);
                backend.publish(responseTopic, reply);
            }
            @Override public void disconnected(MqttDisconnectResponse r) {
                String s = "backend disconnected reasonCode=" + r.getReturnCode() + " reason=" + r.getReasonString();
                reasonHistory.add(s); log.info(s);
            }
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        backend.subscribe(new MqttSubscription[]{ new MqttSubscription("rpc/+/req", 1) }).waitForCompletion();

        device = factory.build("device-" + UUID.randomUUID().toString().substring(0, 6), new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) {
                MqttProperties p = msg.getProperties();
                byte[] correlation = p.getCorrelationData();
                if (correlation == null) return;
                String key = new String(correlation, StandardCharsets.UTF_8);
                CompletableFuture<String> fut = awaitingReplies.remove(key);
                if (fut != null) fut.complete(new String(msg.getPayload(), StandardCharsets.UTF_8));
            }
            @Override public void disconnected(MqttDisconnectResponse r) {
                String s = "device disconnected reasonCode=" + r.getReturnCode() + " reason=" + r.getReasonString();
                reasonHistory.add(s); log.info(s);
            }
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        device.subscribe(new MqttSubscription[]{ new MqttSubscription("rpc/+/resp", 1) }).waitForCompletion();
    }

    /** Send a request via MQTT 5 request/response. Waits up to 5s for the reply. */
    public CompletableFuture<String> request(String reqTopic, String respTopic, String body,
                                             java.util.Map<String, String> userProperties) throws Exception {
        String correlation = UUID.randomUUID().toString();
        CompletableFuture<String> fut = new CompletableFuture<>();
        awaitingReplies.put(correlation, fut);

        MqttMessage msg = new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
        msg.setQos(1);
        MqttProperties p = new MqttProperties();
        p.setResponseTopic(respTopic);
        p.setCorrelationData(correlation.getBytes(StandardCharsets.UTF_8));
        if (userProperties != null) {
            List<UserProperty> up = new ArrayList<>();
            userProperties.forEach((k, v) -> up.add(new UserProperty(k, v)));
            p.setUserProperties(up);
        }
        // Topic alias: setting topicAlias > 0 tells Paho to register an alias for this topic.
        // Subsequent publishes to the same topic skip the topic-name on the wire.
        p.setTopicAlias(1);
        msg.setProperties(p);

        device.publish(reqTopic, msg);
        return fut.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Server-side disconnect with a custom reason code + reason string.
     * Calling this on the backend lets us SHOW MQTT 5 reason codes in action - the
     * device callback will print them. (In MQTT 3 you'd just see "connection closed".)
     */
    public void serverDisconnect(int reasonCode, String reasonString) throws MqttException {
        // 0x8B Server shutting down, 0x97 Quota exceeded, 0x99 Payload format invalid, ...
        device.disconnectForcibly(0, 100, reasonCode);  // sends DISCONNECT packet with that code
    }

    public List<String> reasonHistory() { return new ArrayList<>(reasonHistory); }

    @PreDestroy
    public void close() throws MqttException {
        if (device != null) device.disconnect().waitForCompletion();
        if (backend != null) backend.disconnect().waitForCompletion();
    }
}
