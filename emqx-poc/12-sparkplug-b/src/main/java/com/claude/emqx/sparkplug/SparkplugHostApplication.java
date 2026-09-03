package com.claude.emqx.sparkplug;

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
import org.eclipse.tahu.protobuf.SparkplugBProto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sparkplug B host application — i.e. the SCADA / cloud side.
 *
 * <p>Responsibilities (spec sections 5.4 - 5.7):
 * <ul>
 *   <li>Subscribe to {@code spBv1.0/#} and maintain in-memory state per node and
 *       per child device.</li>
 *   <li>On NBIRTH: establish baseline; record bdSeq.</li>
 *   <li>On NDATA/DDATA: apply metric updates; check seq is monotonic.</li>
 *   <li>On NDEATH: if bdSeq matches the live NBIRTH, mark node stale.</li>
 *   <li>On seq gap: request NCMD/Node Control/Rebirth (this POC logs but does
 *       not actually publish the rebirth command — see TODO in handle()).</li>
 * </ul>
 *
 * <p>This is the half that's almost never written from scratch in production
 * (Ignition, Cirrus Link MQTT Engine, and Inductive Automation all ship one),
 * but doing it forces clarity on what Sparkplug actually guarantees.
 */
@Service
public class SparkplugHostApplication {

    private static final Logger log = LoggerFactory.getLogger(SparkplugHostApplication.class);

    private final PahoMqtt5ClientFactory factory;
    private MqttAsyncClient client;
    private final Map<String, NodeState> state = new ConcurrentHashMap<>();

    public SparkplugHostApplication(MqttClientProperties props) {
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    @PostConstruct
    void start() throws MqttException {
        client = factory.build("host-app", new MqttCallback() {
            @Override public void messageArrived(String topic, MqttMessage msg) {
                handle(topic, msg);
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int rc, MqttProperties p) {}
        });
        client.subscribe(SparkplugTopic.NAMESPACE + "/#", 1).waitForCompletion();
        log.info("host application subscribed to {}/#", SparkplugTopic.NAMESPACE);
    }

    private void handle(String topic, MqttMessage msg) {
        SparkplugTopic t;
        Payload p;
        try {
            t = SparkplugTopic.parse(topic);
            p = Payload.parseFrom(msg.getPayload());
        } catch (Exception e) {
            log.warn("malformed sparkplug message on {}: {}", topic, e.getMessage());
            return;
        }
        String key = nodeKey(t);
        NodeState n = state.computeIfAbsent(key, k -> new NodeState());

        switch (t.msgType()) {
            case "NBIRTH" -> {
                n.bdSeq    = bdSeqFrom(p);
                n.lastSeq  = p.getSeq();
                n.alive    = true;
                n.metrics.clear();
                n.metrics.putAll(PayloadCodec.toMap(p));
                log.info("NBIRTH {} bdSeq={} metrics={}", key, n.bdSeq, n.metrics.keySet());
            }
            case "NDATA" -> {
                checkSeq(key, n, p.getSeq());
                n.metrics.putAll(PayloadCodec.toMap(p));
                log.info("NDATA {} seq={} updated={}", key, p.getSeq(), PayloadCodec.toMap(p));
            }
            case "NDEATH" -> {
                long deathBd = bdSeqFrom(p);
                if (deathBd == n.bdSeq) {
                    n.alive = false;
                    log.warn("NDEATH {} bdSeq={} - node stale", key, deathBd);
                } else {
                    log.info("NDEATH {} bdSeq mismatch ({} vs live {}) - ignored", key, deathBd, n.bdSeq);
                }
            }
            case "DBIRTH" -> {
                DeviceState d = n.devices.computeIfAbsent(t.deviceId(), k -> new DeviceState());
                d.alive = true;
                d.metrics.clear();
                d.metrics.putAll(PayloadCodec.toMap(p));
                log.info("DBIRTH {}/{} metrics={}", key, t.deviceId(), d.metrics.keySet());
            }
            case "DDATA" -> {
                checkSeq(key, n, p.getSeq());
                DeviceState d = n.devices.computeIfAbsent(t.deviceId(), k -> new DeviceState());
                d.metrics.putAll(PayloadCodec.toMap(p));
                log.info("DDATA {}/{} seq={} updated={}", key, t.deviceId(), p.getSeq(), PayloadCodec.toMap(p));
            }
            case "DDEATH" -> {
                DeviceState d = n.devices.get(t.deviceId());
                if (d != null) d.alive = false;
                log.warn("DDEATH {}/{} - device stale", key, t.deviceId());
            }
            default -> log.debug("unhandled msg_type {} on {}", t.msgType(), topic);
        }
    }

    private void checkSeq(String key, NodeState n, long incoming) {
        long expected = (n.lastSeq + 1) & 0xFF;
        if (incoming != expected) {
            // TODO: in a real host app, publish NCMD with "Node Control/Rebirth = true"
            //       on spBv1.0/{group}/NCMD/{node} and the edge node will re-NBIRTH.
            log.warn("seq gap on {}: expected={} got={} → would request rebirth", key, expected, incoming);
        }
        n.lastSeq = incoming;
    }

    private static long bdSeqFrom(Payload p) {
        return p.getMetricsList().stream()
                .filter(m -> PayloadCodec.BD_SEQ.equals(m.getName()))
                .findFirst()
                .map(m -> m.getLongValue())
                .orElse(-1L);
    }

    private static String nodeKey(SparkplugTopic t) {
        return t.groupId() + "/" + t.edgeNodeId();
    }

    public Map<String, NodeState> snapshot() { return state; }

    public static class NodeState {
        public boolean alive;
        public long bdSeq;
        public long lastSeq = -1;
        public final Map<String, Object> metrics = new LinkedHashMap<>();
        public final Map<String, DeviceState> devices = new ConcurrentHashMap<>();
    }

    public static class DeviceState {
        public boolean alive;
        public final Map<String, Object> metrics = new LinkedHashMap<>();
    }
}
