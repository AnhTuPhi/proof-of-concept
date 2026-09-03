package com.claude.emqx.lwt;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.util.Json;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns simulated devices that connect with an MQTT 5 LWT (Last Will & Testament).
 *
 * <p>The LWT pattern (the MQTT-native way to do presence):
 *
 * <ol>
 *   <li>Device connects, sets:
 *     <ul>
 *       <li>Will topic: {@code presence/{deviceId}}</li>
 *       <li>Will payload: {@code {"status":"offline","ts":...}}</li>
 *       <li>Will retain: true (so late subscribers see current state)</li>
 *       <li>Will delay interval: small (e.g. 5s) so we don't fire on brief blips</li>
 *     </ul>
 *   </li>
 *   <li>Device publishes a retained {@code {"status":"online",...}} immediately after connect.</li>
 *   <li>If device disconnects ungracefully (TCP RST, keepalive timeout, network down):
 *     <ul>
 *       <li>Broker waits {@code willDelayInterval} seconds</li>
 *       <li>If device hasn't reconnected, broker publishes the Will message</li>
 *       <li>Subscribers see the device go offline</li>
 *     </ul>
 *   </li>
 *   <li>If device disconnects gracefully (sends DISCONNECT), broker discards the Will
 *       UNLESS you set {@code sendWillOnDisconnect=true} on the DISCONNECT packet.</li>
 * </ol>
 *
 * <p>The {@code willDelayInterval} is the MQTT 5 super-power: in MQTT 3 the will fired
 * immediately on any disconnect, which made brief network blips trigger false offline
 * events. MQTT 5 lets you delay so reconnects within the window suppress the will.
 */
@Service
public class LwtDeviceFactory {

    private static final Logger log = LoggerFactory.getLogger(LwtDeviceFactory.class);

    private final MqttClientProperties props;
    private final Map<String, MqttAsyncClient> devices = new ConcurrentHashMap<>();

    public LwtDeviceFactory(MqttClientProperties props) { this.props = props; }

    /**
     * @param keepAliveSec controls how fast the broker detects a hard disconnect
     *                     (typical: 60s = up to 90s to detect missing PINGs)
     * @param willDelaySec the will delay; 0 = fire immediately, like MQTT 3
     */
    public MqttAsyncClient spawnDevice(String deviceId, int keepAliveSec, int willDelaySec) throws MqttException {
        String clientId = "lwt-" + deviceId;
        MqttAsyncClient client = new MqttAsyncClient(
                props.brokerUrl(), clientId, new MemoryPersistence());

        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setUserName(props.username());
        opts.setPassword(props.password().getBytes(StandardCharsets.UTF_8));
        opts.setCleanStart(true);
        opts.setSessionExpiryInterval(0);
        opts.setKeepAliveInterval(keepAliveSec);

        // ----- LWT setup -----
        // 1. Build the will message
        Map<String, Object> willPayload = Map.of(
                "deviceId", deviceId,
                "status", "offline",
                "reason", "lwt-fired",
                "ts", Instant.now().toString()
        );
        MqttMessage will = new MqttMessage(Json.toBytes(willPayload));
        will.setQos(1);
        will.setRetained(true);   // retain so the next subscriber sees current state
        // 2. Set the will topic and message
        opts.setWillDestination("presence/" + deviceId);
        opts.setWillMessageProperties(new org.eclipse.paho.mqttv5.common.packet.MqttProperties());
        opts.getWillMessageProperties().setWillDelayInterval(willDelaySec);
        opts.setWillMessage(will);

        client.connect(opts).waitForCompletion();

        // 3. Publish "online" RETAINED so subscribers know the device is healthy.
        // We do this AFTER connect, otherwise CONNACK isn't done yet and the publish
        // can race with the willMessage on reconnect.
        Map<String, Object> online = Map.of(
                "deviceId", deviceId, "status", "online",
                "ts", Instant.now().toString());
        MqttMessage onlineMsg = new MqttMessage(Json.toBytes(online));
        onlineMsg.setQos(1);
        onlineMsg.setRetained(true);
        client.publish("presence/" + deviceId, onlineMsg);

        devices.put(deviceId, client);
        log.info("Spawned device {} with keepAlive={}s willDelay={}s", deviceId, keepAliveSec, willDelaySec);
        return client;
    }

    /** Graceful disconnect. By default, LWT is NOT fired. */
    public boolean gracefulShutdown(String deviceId) throws MqttException {
        MqttAsyncClient c = devices.remove(deviceId);
        if (c == null) return false;
        // Publish offline retained ourselves so subscribers see it immediately
        // (otherwise they have no event - the will was suppressed).
        Map<String, Object> offline = Map.of(
                "deviceId", deviceId, "status", "offline", "reason", "graceful", "ts", Instant.now().toString());
        MqttMessage msg = new MqttMessage(Json.toBytes(offline));
        msg.setQos(1);
        msg.setRetained(true);
        c.publish("presence/" + deviceId, msg);
        c.disconnect().waitForCompletion();
        return true;
    }

    /** Hard kill - tears down the TCP socket without DISCONNECT. LWT WILL fire. */
    public boolean hardKill(String deviceId) throws MqttException {
        MqttAsyncClient c = devices.remove(deviceId);
        if (c == null) return false;
        // disconnectForcibly with quiesce=0 and disconnectTimeout=0
        // skips the DISCONNECT packet entirely - looks like a network drop.
        c.disconnectForcibly(0, 0, false);
        c.close(true);
        log.info("Hard-killed device {} - LWT will fire after willDelay", deviceId);
        return true;
    }

    @PreDestroy
    public void shutdown() {
        devices.values().forEach(c -> {
            try { c.disconnect().waitForCompletion(2000); } catch (Exception ignore) {}
        });
    }
}
