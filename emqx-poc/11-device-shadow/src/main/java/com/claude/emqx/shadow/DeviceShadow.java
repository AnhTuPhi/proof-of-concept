package com.claude.emqx.shadow;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import com.claude.emqx.common.util.Json;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Device Shadow on EMQX.
 *
 * <p>Mental model (same as AWS IoT Shadow):
 * <ul>
 *   <li><b>reported</b>: what the device last said it is doing</li>
 *   <li><b>desired</b>: what the backend wants the device to do</li>
 *   <li><b>delta</b>: where they differ. Devices subscribe to delta and converge.</li>
 * </ul>
 *
 * <p>Topic convention (consistent with AWS so devices can be moved):
 * <pre>
 *   $devices/{deviceId}/shadow/update         -- device POSTS its reported state here
 *   $devices/{deviceId}/shadow/get            -- device asks for current shadow
 *   $devices/{deviceId}/shadow/get/accepted   -- shadow sends snapshot here (retained)
 *   $devices/{deviceId}/shadow/update/delta   -- shadow publishes here when desired != reported (retained)
 * </pre>
 *
 * <p>This service:
 * 1. Subscribes to update topics, persists the reported state in Postgres,
 *    computes the delta against the desired, publishes delta when needed.
 * 2. Exposes REST endpoints for backend to PATCH the desired state.
 *
 * <p>Storage is in Postgres (table device_state) so the shadow survives broker restarts.
 */
@Service
public class DeviceShadow {

    private static final Logger log = LoggerFactory.getLogger(DeviceShadow.class);

    private final PahoMqtt5ClientFactory factory;
    private final JdbcTemplate jdbc;
    private MqttAsyncClient client;

    public DeviceShadow(MqttClientProperties props, JdbcTemplate jdbc) {
        this.factory = new PahoMqtt5ClientFactory(props);
        this.jdbc = jdbc;
    }

    @PostConstruct
    void start() throws MqttException {
        client = factory.build("shadow-svc", new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) throws Exception {
                // topic format: $devices/<deviceId>/shadow/update
                String[] parts = topic.split("/");
                if (parts.length < 4) return;
                String deviceId = parts[1];
                String action = parts[3];  // update | get
                if ("update".equals(action)) {
                    handleReported(deviceId, msg.getPayload());
                } else if ("get".equals(action)) {
                    publishSnapshot(deviceId);
                }
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        client.subscribe("$devices/+/shadow/update", 1).waitForCompletion();
        client.subscribe("$devices/+/shadow/get", 1).waitForCompletion();
        log.info("DeviceShadow subscribed");
    }

    @SuppressWarnings("unchecked")
    private void handleReported(String deviceId, byte[] payload) throws Exception {
        Map<String, Object> reported = Json.fromBytes(payload, Map.class);
        // Upsert the reported state (JSONB merge - the || operator does shallow merge)
        jdbc.update("""
                INSERT INTO device_state (device_id, reported, reported_at, version)
                VALUES (?, ?::jsonb, now(), 1)
                ON CONFLICT (device_id)
                DO UPDATE SET reported = device_state.reported || EXCLUDED.reported,
                              reported_at = EXCLUDED.reported_at,
                              version = device_state.version + 1
                """,
                deviceId, new String(Json.toBytes(reported)));

        // Compute delta = desired - reported (set-difference at JSON-key level)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reported::text AS reported, desired::text AS desired FROM device_state WHERE device_id = ?",
                deviceId);
        Map<String, Object> desired     = parseJson((String) row.get("desired"));
        Map<String, Object> reportedNow = parseJson((String) row.get("reported"));
        Map<String, Object> delta = computeDelta(desired, reportedNow);
        if (!delta.isEmpty()) {
            publishDelta(deviceId, delta);
        } else {
            // Reported has caught up - clear delta from retained
            clearDelta(deviceId);
        }
    }

    public void setDesired(String deviceId, Map<String, Object> desired) throws Exception {
        jdbc.update("""
                INSERT INTO device_state (device_id, desired, desired_at)
                VALUES (?, ?::jsonb, now())
                ON CONFLICT (device_id)
                DO UPDATE SET desired = device_state.desired || EXCLUDED.desired,
                              desired_at = EXCLUDED.desired_at,
                              version = device_state.version + 1
                """,
                deviceId, new String(Json.toBytes(desired)));
        // Compute and publish delta immediately
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reported::text AS reported, desired::text AS desired FROM device_state WHERE device_id = ?",
                deviceId);
        Map<String, Object> reported = parseJson((String) row.get("reported"));
        Map<String, Object> mergedDesired = parseJson((String) row.get("desired"));
        Map<String, Object> delta = computeDelta(mergedDesired, reported);
        if (!delta.isEmpty()) publishDelta(deviceId, delta);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String s) {
        if (s == null || s.isBlank()) return new HashMap<>();
        return Json.fromBytes(s.getBytes(StandardCharsets.UTF_8), Map.class);
    }

    private Map<String, Object> computeDelta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new HashMap<>();
        for (var e : desired.entrySet()) {
            Object r = reported.get(e.getKey());
            if (r == null || !r.equals(e.getValue())) delta.put(e.getKey(), e.getValue());
        }
        return delta;
    }

    private void publishDelta(String deviceId, Map<String, Object> delta) throws MqttException {
        MqttMessage m = new MqttMessage(Json.toBytes(delta));
        m.setQos(1);
        m.setRetained(true);   // device gets delta even if it connects later
        MqttProperties p = new MqttProperties();
        p.setMessageExpiryInterval(86400);  // 24h TTL (POC 10 lesson)
        m.setProperties(p);
        client.publish("$devices/" + deviceId + "/shadow/update/delta", m);
        log.info("delta published for {} -> {}", deviceId, delta);
    }

    private void clearDelta(String deviceId) throws MqttException {
        MqttMessage m = new MqttMessage(new byte[0]);
        m.setQos(1);
        m.setRetained(true);
        client.publish("$devices/" + deviceId + "/shadow/update/delta", m);
    }

    private void publishSnapshot(String deviceId) throws MqttException {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT reported::text AS reported, desired::text AS desired, version
                FROM device_state WHERE device_id = ?""", deviceId);
        Map<String, Object> snapshot = Map.of(
                "state", Map.of(
                        "reported", parseJson((String) row.get("reported")),
                        "desired",  parseJson((String) row.get("desired"))),
                "version", row.get("version"));
        MqttMessage m = new MqttMessage(Json.toBytes(snapshot));
        m.setQos(1);
        client.publish("$devices/" + deviceId + "/shadow/get/accepted", m);
    }

    public Map<String, Object> snapshot(String deviceId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT device_id, reported::text AS reported, desired::text AS desired,
                       version, reported_at, desired_at
                FROM device_state WHERE device_id = ?""", deviceId);
        return Map.of(
                "deviceId",    row.get("device_id"),
                "reported",    parseJson((String) row.get("reported")),
                "desired",     parseJson((String) row.get("desired")),
                "version",     row.get("version"),
                "reportedAt",  String.valueOf(row.get("reported_at")),
                "desiredAt",   String.valueOf(row.get("desired_at")));
    }
}
