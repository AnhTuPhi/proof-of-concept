package com.claude.emqx.lwt;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Subscribes to {@code presence/+} and tracks the latest state per device.
 * Acts as the "backend" that consumes presence events.
 */
@Service
public class PresenceObserver {

    private static final Logger log = LoggerFactory.getLogger(PresenceObserver.class);

    private final PahoMqtt5ClientFactory factory;
    private final Map<String, String> currentStatus = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> eventLog = new ConcurrentLinkedDeque<>();

    public PresenceObserver(MqttClientProperties props) {
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    @PostConstruct
    void start() throws MqttException {
        MqttAsyncClient client = factory.build("presence-observer", new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) {
                String payload = new String(msg.getPayload());
                String deviceId = topic.substring(topic.lastIndexOf('/') + 1);
                currentStatus.put(deviceId, payload);
                eventLog.addFirst("[" + java.time.Instant.now() + "] " + topic + " -> " + payload);
                while (eventLog.size() > 200) eventLog.pollLast();
                log.info("PRESENCE {} -> {}", deviceId, payload);
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        // Subscribe with QoS 1 to ensure we don't lose the offline event
        client.subscribe("presence/+", 1).waitForCompletion();
        log.info("PresenceObserver subscribed to presence/+");
    }

    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>(currentStatus);
        return out;
    }

    public java.util.List<String> recentEvents() { return new java.util.ArrayList<>(eventLog); }
}
