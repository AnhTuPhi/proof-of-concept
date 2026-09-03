package com.claude.emqx.sharedsub;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import com.claude.emqx.common.model.Telemetry;
import com.claude.emqx.common.util.Json;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Producer for the shared-sub POC. Publishes to the NORMAL topic (not the
 * $share filter); the broker delivers each message to ONE consumer per group.
 */
@Service
public class Producer {

    private final PahoMqtt5ClientFactory factory;
    private MqttAsyncClient client;

    public Producer(MqttClientProperties props) {
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    private synchronized MqttAsyncClient client() throws MqttException {
        if (client == null) {
            client = factory.build("producer-" + UUID.randomUUID().toString().substring(0, 8),
                    PahoMqtt5ClientFactory.loggingCallback("producer"));
        }
        return client;
    }

    public void produce(String topic, int count, int qos) throws MqttException {
        MqttAsyncClient c = client();
        for (int i = 0; i < count; i++) {
            Telemetry t = Telemetry.sample("dev-" + (i % 50), i);
            MqttMessage m = new MqttMessage(Json.toBytes(t));
            m.setQos(qos);
            c.publish(topic, m);
        }
    }
}
