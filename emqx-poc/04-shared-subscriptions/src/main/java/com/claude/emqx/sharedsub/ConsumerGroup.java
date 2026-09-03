package com.claude.emqx.sharedsub;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import com.claude.emqx.common.metrics.MqttMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Demonstrates the EMQX/MQTT 5 shared-subscription pattern - the equivalent
 * of Kafka consumer groups, with one critical difference:
 *
 * <p><b>The broker holds the load-balancing logic, not the consumers.</b>
 * In Kafka, consumers coordinate their partition ownership via the group
 * coordinator. In MQTT, consumers just all subscribe to
 * {@code $share/group/topic}, and the broker picks one consumer per message
 * using one of four strategies (set at the broker level):
 *
 * <ul>
 *   <li>{@code random}      - default, uniform load but no locality</li>
 *   <li>{@code round_robin} - fairer for low-rate topics, slight broker cost</li>
 *   <li>{@code sticky}      - hash by ClientID, so the same consumer keeps
 *                            seeing the same publisher's messages (cache-friendly)</li>
 *   <li>{@code hash_clientid} - similar to sticky but on the publisher's ID</li>
 *   <li>{@code hash_topic}  - useful when fanout topics share suffixes</li>
 * </ul>
 *
 * <p>To switch: edit {@code mqtt.shared_subscription_strategy} in emqx.conf.
 */
@Service
public class ConsumerGroup {

    private static final Logger log = LoggerFactory.getLogger(ConsumerGroup.class);

    private final PahoMqtt5ClientFactory factory;
    private final MqttMetrics metrics;

    private final List<MqttAsyncClient> consumers = new ArrayList<>();
    private final Map<String, LongAdder> perConsumerCount = new ConcurrentHashMap<>();

    public ConsumerGroup(MqttClientProperties props, MeterRegistry reg) {
        this.factory = new PahoMqtt5ClientFactory(props);
        this.metrics = new MqttMetrics(reg, "04-shared-sub");
    }

    /**
     * Spin up {@code n} consumers, all subscribing to {@code $share/{group}/{topic}}.
     * Returns a snapshot of per-consumer counters so callers can confirm the
     * load is distributed (rather than all hitting one consumer).
     */
    public List<String> startConsumers(String group, String topic, int n, int qos) throws MqttException {
        for (int i = 0; i < n; i++) {
            String suffix = "g-" + group + "-c" + i;
            LongAdder counter = new LongAdder();
            perConsumerCount.put(suffix, counter);

            MqttAsyncClient client = factory.build(suffix, new MqttCallback() {
                @Override public void messageArrived(String t, MqttMessage msg) {
                    counter.increment();
                    metrics.receiveCount.increment();
                }
                @Override public void disconnected(MqttDisconnectResponse r) {}
                @Override public void mqttErrorOccurred(MqttException ex) {}
                @Override public void deliveryComplete(IMqttToken t) {}
                @Override public void connectComplete(boolean reconnect, String uri) {}
                @Override public void authPacketArrived(int rc, MqttProperties p) {}
            });

            // KEY LINE: subscribe to the shared filter.
            // Format: $share/<group>/<topicFilter>
            //   group identifies the consumer-group; multiple consumers with the same
            //   group share the messages. Multiple groups each receive a full copy
            //   (this is fanout-of-groups, like Kafka with two consumer-groups on a topic).
            String sharedFilter = "$share/" + group + "/" + topic;
            client.subscribe(sharedFilter, qos).waitForCompletion(5000);
            log.info("Consumer {} subscribed to {}", suffix, sharedFilter);
            consumers.add(client);
        }
        return consumers.stream().map(c -> c.getClientId()).toList();
    }

    public Map<String, Long> distributionSnapshot() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        perConsumerCount.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public int size() { return consumers.size(); }

    @PreDestroy
    public void shutdown() {
        for (MqttAsyncClient c : consumers) {
            try { c.disconnect().waitForCompletion(2000); } catch (Exception ignore) {}
        }
    }
}
