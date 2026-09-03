package com.claude.emqx.retained;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class RetainedDemo {

    private static final Logger log = LoggerFactory.getLogger(RetainedDemo.class);

    private final PahoMqtt5ClientFactory factory;
    private MqttAsyncClient client;

    public RetainedDemo(MqttClientProperties props) {
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    private synchronized MqttAsyncClient client() throws MqttException {
        if (client == null) {
            client = factory.build("retained-" + UUID.randomUUID().toString().substring(0, 6),
                    PahoMqtt5ClientFactory.loggingCallback("retained"));
        }
        return client;
    }

    /**
     * Publish a retained message with an explicit per-message TTL.
     *
     * <p>MQTT 5's {@code messageExpiryInterval} is per-message; this is the
     * preferred mechanism over the broker-wide TTL. It lets a "current config"
     * retained message live 24h while a "current alarm" retained message
     * lives only 5 min.
     */
    public void setRetained(String topic, String payload, long ttlSeconds) throws MqttException {
        MqttMessage m = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        m.setQos(1);
        m.setRetained(true);
        MqttProperties p = new MqttProperties();
        if (ttlSeconds > 0) p.setMessageExpiryInterval(ttlSeconds);
        m.setProperties(p);
        client().publish(topic, m);
        log.info("retained set: {} ttl={}s", topic, ttlSeconds);
    }

    /**
     * Clear a retained message - publish a ZERO-byte retained payload to the
     * same topic. This is the spec-mandated way to delete.
     */
    public void clearRetained(String topic) throws MqttException {
        MqttMessage m = new MqttMessage(new byte[0]);
        m.setQos(1);
        m.setRetained(true);
        client().publish(topic, m);
        log.info("retained cleared: {}", topic);
    }

    /**
     * Republish-spam: simulate the bug where a device publishes a retained
     * "current state" message every time anything changes - the retainer
     * table grows fast, especially when the topics include unique IDs.
     */
    public int spamRetained(String topicPrefix, int n) throws MqttException {
        for (int i = 0; i < n; i++) {
            String topic = topicPrefix + "/" + i;
            setRetained(topic, "{\"state\":\"on\",\"i\":" + i + "}", 0);  // 0 = no TTL!
        }
        return n;
    }

    @PreDestroy
    public void close() throws MqttException {
        if (client != null) client.disconnect().waitForCompletion();
    }
}
