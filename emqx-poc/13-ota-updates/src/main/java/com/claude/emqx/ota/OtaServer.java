package com.claude.emqx.ota;

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
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTA update service. Responsibilities:
 *
 * <ul>
 *   <li>Hold the catalog of active firmware images.</li>
 *   <li>Publish a retained "offer" per device class to {@code ota/{class}/offer} so
 *       devices learn about new firmware on their next connect (POC 10 pattern).
 *       Retained with a 30-day TTL, then re-published on each campaign refresh.</li>
 *   <li>Subscribe to {@code ota/+/request} for chunk requests and reply on
 *       {@code ota/{deviceId}/chunk/{n}}.</li>
 *   <li>Subscribe to {@code ota/+/status} for progress and persist for visibility.</li>
 * </ul>
 *
 * <p>The chunked-pull pattern (device pulls, server doesn't push the whole image)
 * is deliberate. It gives:
 * <ul>
 *   <li><b>Backpressure</b>: device sets the rate; slow devices don't drown.</li>
 *   <li><b>Resumability</b>: device asks for the chunks it's missing after a crash.</li>
 *   <li><b>Stagger</b>: a million devices hashing their wakeup gives natural rollout.</li>
 * </ul>
 */
@Service
public class OtaServer {

    private static final Logger log = LoggerFactory.getLogger(OtaServer.class);

    private final PahoMqtt5ClientFactory factory;
    private MqttAsyncClient client;
    private final Map<String, Firmware> catalog = new ConcurrentHashMap<>();   // targetClass -> Firmware
    private final Map<String, String>   progress = new ConcurrentHashMap<>();  // deviceId -> last status JSON

    public OtaServer(MqttClientProperties props) {
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    @PostConstruct
    void start() throws MqttException {
        client = factory.build("ota-server", new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) throws Exception {
                handle(topic, msg);
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        client.subscribe("ota/+/request", 1).waitForCompletion();
        client.subscribe("ota/+/status",  1).waitForCompletion();
        log.info("OTA server subscribed");
    }

    /**
     * Register a firmware image and broadcast the offer.
     * Offer is retained — devices connecting later get it immediately (POC 10).
     */
    public Firmware publishCampaign(String targetClass, String version, byte[] bytes, int chunkSize) throws MqttException {
        Firmware fw = Firmware.of(version, targetClass, bytes, chunkSize);
        catalog.put(targetClass, fw);

        Map<String, Object> offer = Map.of(
                "version",     fw.version(),
                "sha256",      fw.sha256(),
                "size",        fw.bytes().length,
                "chunkSize",   fw.chunkSize(),
                "totalChunks", fw.totalChunks());

        MqttMessage m = new MqttMessage(Json.toBytes(offer));
        m.setQos(1);
        m.setRetained(true);
        MqttProperties p = new MqttProperties();
        p.setMessageExpiryInterval(30L * 24 * 3600);   // 30 days (POC 10 lesson)
        m.setProperties(p);
        client.publish("ota/" + targetClass + "/offer", m);

        log.info("campaign published: class={} version={} size={} chunks={} sha256={}",
                targetClass, version, bytes.length, fw.totalChunks(), fw.sha256());
        return fw;
    }

    public void cancelCampaign(String targetClass) throws MqttException {
        catalog.remove(targetClass);
        MqttMessage m = new MqttMessage(new byte[0]);
        m.setQos(1);
        m.setRetained(true);
        client.publish("ota/" + targetClass + "/offer", m);   // empty retained = delete
        log.info("campaign cancelled: class={}", targetClass);
    }

    @SuppressWarnings("unchecked")
    private void handle(String topic, MqttMessage msg) throws Exception {
        String[] parts = topic.split("/");   // ota/{deviceId}/{request|status}
        if (parts.length < 3) return;
        String deviceId = parts[1];
        String kind = parts[2];

        if ("request".equals(kind)) {
            Map<String, Object> req = Json.fromBytes(msg.getPayload(), Map.class);
            String targetClass = (String) req.get("class");
            String version     = (String) req.get("version");
            int from           = ((Number) req.get("from")).intValue();
            int to             = ((Number) req.get("to")).intValue();

            Firmware fw = catalog.get(targetClass);
            if (fw == null || !fw.version().equals(version)) {
                log.warn("device {} requested unknown fw class={} version={}", deviceId, targetClass, version);
                return;
            }
            for (int i = from; i <= to && i < fw.totalChunks(); i++) {
                byte[] data = fw.chunk(i);
                MqttMessage cm = new MqttMessage(data);
                cm.setQos(1);   // each chunk is acked; lost chunks get re-requested
                cm.setRetained(false);
                client.publish("ota/" + deviceId + "/chunk/" + i, cm);
            }
            log.info("served chunks {}-{} to {}", from, to, deviceId);

        } else if ("status".equals(kind)) {
            String json = new String(msg.getPayload());
            progress.put(deviceId, json);
            log.info("status from {}: {}", deviceId, json);
        }
    }

    public Map<String, String> progress() { return progress; }
    public Map<String, Firmware> catalog() { return catalog; }
}
