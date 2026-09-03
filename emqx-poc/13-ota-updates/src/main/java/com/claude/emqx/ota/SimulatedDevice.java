package com.claude.emqx.ota;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import com.claude.emqx.common.util.Json;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

/**
 * Simulated edge device. Subscribes to its class's offer topic, downloads chunks,
 * verifies SHA-256, "applies" by storing into an in-memory slot.
 *
 * <p>The download is pull-style: the device asks for ranges of chunks. This is
 * resumable — after a restart, a real device reads its persisted bitmap of
 * received chunks and asks only for the gaps. This POC keeps the bitmap in
 * memory (resets on restart, which is fine for the demo).
 */
public class SimulatedDevice {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDevice.class);

    private final String deviceId;
    private final String targetClass;
    private final MqttClientProperties props;
    private MqttAsyncClient client;

    // Current pending download. nullable.
    private volatile String  pendingVersion;
    private volatile int     pendingChunkSize;
    private volatile int     pendingTotalChunks;
    private volatile String  pendingSha256;
    private volatile byte[]  buffer;
    private volatile BitSet  received;

    private volatile String activeVersion = "v0.0.0";   // pretend baseline

    public SimulatedDevice(String deviceId, String targetClass, MqttClientProperties props) {
        this.deviceId = deviceId;
        this.targetClass = targetClass;
        this.props = props;
    }

    public void connect() throws MqttException {
        client = new PahoMqtt5ClientFactory(props).build("device-" + deviceId, new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) throws Exception {
                onMessage(topic, msg);
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        // Sub to class offer (retained → we'll get whatever's current immediately)
        client.subscribe("ota/" + targetClass + "/offer",     1).waitForCompletion();
        client.subscribe("ota/" + deviceId    + "/chunk/+", 1).waitForCompletion();
        log.info("device {} connected, listening for class={} offers", deviceId, targetClass);
    }

    @SuppressWarnings("unchecked")
    private void onMessage(String topic, MqttMessage msg) throws Exception {
        if (topic.endsWith("/offer")) {
            if (msg.getPayload().length == 0) {
                log.info("[{}] campaign cancelled", deviceId);
                pendingVersion = null;
                return;
            }
            Map<String, Object> offer = Json.fromBytes(msg.getPayload(), Map.class);
            String version = (String) offer.get("version");
            if (version.equals(activeVersion)) return;
            if (version.equals(pendingVersion)) return;   // already downloading

            pendingVersion     = version;
            pendingChunkSize   = ((Number) offer.get("chunkSize")).intValue();
            pendingTotalChunks = ((Number) offer.get("totalChunks")).intValue();
            pendingSha256      = (String) offer.get("sha256");
            buffer             = new byte[((Number) offer.get("size")).intValue()];
            received           = new BitSet(pendingTotalChunks);
            log.info("[{}] starting download of {} ({} chunks)", deviceId, version, pendingTotalChunks);
            publishStatus("downloading", 0);
            requestRange(0, Math.min(15, pendingTotalChunks - 1));   // first window: 16 chunks

        } else if (topic.contains("/chunk/")) {
            int idx = Integer.parseInt(topic.substring(topic.lastIndexOf('/') + 1));
            if (pendingVersion == null) return;   // stale chunk from cancelled campaign
            int from = idx * pendingChunkSize;
            byte[] data = msg.getPayload();
            System.arraycopy(data, 0, buffer, from, data.length);
            received.set(idx);
            // Slide the window: every 8 chunks ack and request the next 8
            if (received.cardinality() % 8 == 0 || received.cardinality() == pendingTotalChunks) {
                publishStatus("downloading", 100 * received.cardinality() / pendingTotalChunks);
                int nextStart = received.length();   // index of highest set bit + 1
                if (nextStart < pendingTotalChunks) {
                    requestRange(nextStart, Math.min(nextStart + 7, pendingTotalChunks - 1));
                }
            }
            if (received.cardinality() == pendingTotalChunks) {
                verifyAndApply();
            }
        }
    }

    private void requestRange(int from, int to) throws MqttException {
        Map<String, Object> req = Map.of(
                "class", targetClass, "version", pendingVersion,
                "from", from, "to", to);
        MqttMessage m = new MqttMessage(Json.toBytes(req));
        m.setQos(1);
        client.publish("ota/" + deviceId + "/request", m);
    }

    private void verifyAndApply() throws MqttException {
        publishStatus("verifying", 100);
        String got = Firmware.hash(buffer);
        if (!got.equals(pendingSha256)) {
            log.error("[{}] sha mismatch! got={} expected={}", deviceId, got, pendingSha256);
            publishStatus("failed", 100);
            // In a real device: keep the active slot, drop pending.
            return;
        }
        // Atomic-ish swap. A real device would write to inactive slot, then flip
        // the bootloader pointer, then reboot.
        activeVersion = pendingVersion;
        log.info("[{}] applied {} ({} bytes)", deviceId, pendingVersion, buffer.length);
        publishStatus("applied", 100);
        pendingVersion = null;
        buffer = null;
        received = null;
    }

    private void publishStatus(String state, int pct) {
        try {
            Map<String, Object> s = Map.of(
                    "active",  activeVersion,
                    "pending", pendingVersion == null ? "" : pendingVersion,
                    "state",   state,
                    "percent", pct);
            MqttMessage m = new MqttMessage(Json.toBytes(s));
            m.setQos(1);
            client.publish("ota/" + deviceId + "/status", m);
        } catch (Exception ignored) {}
    }

    public void disconnect() throws MqttException {
        if (client != null) client.disconnect().waitForCompletion(2000);
    }

    public String deviceId()      { return deviceId; }
    public String activeVersion() { return activeVersion; }
    public String pendingVersion(){ return pendingVersion; }
    public int    receivedCount() { return received == null ? 0 : received.cardinality(); }
}
