package com.claude.emqx.sparkplug;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.tahu.protobuf.SparkplugBProto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Sparkplug B edge node.
 *
 * <p>Lifecycle (Sparkplug B v3, sections 5.4-5.6):
 * <ol>
 *   <li><b>Connect with NDEATH as LWT.</b> The broker holds onto our NDEATH and
 *       publishes it for us if we vanish ungracefully. bdSeq inside the NDEATH
 *       must match the bdSeq in our NBIRTH so the host can correlate.</li>
 *   <li><b>Publish NBIRTH (retained false, QoS 0).</b> Declares all metrics we'll
 *       send. Sets {@code seq=0}.</li>
 *   <li><b>Publish NDATA</b> as state changes. {@code seq} increments 0→255 then
 *       wraps. Any gap in seq tells the host messages were dropped.</li>
 *   <li>On clean shutdown, publish NDEATH ourselves, then disconnect.</li>
 * </ol>
 *
 * <p>Why NDEATH-as-LWT is critical: an MQTT keepalive timeout only fires after
 * 1.5× the keepalive interval. With 30s keepalive that's 45s of silent failure
 * before the host hears anything. Sparkplug's LWT pattern hands the dead-node
 * signal to the broker, which fires NDEATH the moment the TCP connection drops.
 */
public class SparkplugEdgeNode {

    private static final Logger log = LoggerFactory.getLogger(SparkplugEdgeNode.class);

    private final String groupId;
    private final String nodeId;
    private final MqttClientProperties props;
    private MqttAsyncClient client;
    private final AtomicLong seq = new AtomicLong(0);
    private final long bdSeq;       // birth/death pairing seq, see spec 5.4.1

    public SparkplugEdgeNode(String groupId, String nodeId, MqttClientProperties props) {
        this.groupId = groupId;
        this.nodeId = nodeId;
        this.props = props;
        this.bdSeq = System.currentTimeMillis() & 0xFF;  // any monotonic value; spec only says it must change per session
    }

    /** Connect with NDEATH set as MQTT LWT, then publish NBIRTH. */
    public void online() throws MqttException {
        // 1. Build the NDEATH payload that the broker will publish on our behalf.
        Payload death = PayloadCodec.newPayload(0)
                .addMetrics(PayloadCodec.metric(PayloadCodec.BD_SEQ, SparkplugDataType.INT64, bdSeq))
                .build();

        // 2. Connect with NDEATH wired into MqttConnectionOptions.willMessage.
        //    We can't use PahoMqtt5ClientFactory directly because it doesn't expose
        //    LWT yet; build the client by hand so we own the connect opts.
        String clientId = props.clientIdPrefix() + "-" + nodeId + "-" + UUID.randomUUID();
        client = new MqttAsyncClient(props.brokerUrl(), clientId, new MemoryPersistence());

        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setUserName(props.username());
        opts.setPassword(props.password() == null ? null : props.password().getBytes(StandardCharsets.UTF_8));
        opts.setCleanStart(true);   // edge nodes always cleanStart=true per Sparkplug spec
        opts.setKeepAliveInterval(props.keepAliveSeconds());
        opts.setAutomaticReconnect(false);   // Sparkplug requires fresh NBIRTH on reconnect, not transparent resume
        opts.setSessionExpiryInterval(0L);

        MqttMessage will = new MqttMessage(death.toByteArray());
        will.setQos(1);
        will.setRetained(false);
        opts.setWillDestination(SparkplugTopic.node(groupId, "NDEATH", nodeId).render());
        opts.setWillMessageProperties(new org.eclipse.paho.mqttv5.common.packet.MqttProperties());
        opts.setWillMessage(will);

        client.connect(opts).waitForCompletion(5000);
        log.info("edge node {}/{} connected (bdSeq={})", groupId, nodeId, bdSeq);

        // 3. Publish NBIRTH. MUST include bdSeq metric.
        Payload birth = PayloadCodec.newPayload(nextSeq())
                .addMetrics(PayloadCodec.metric(PayloadCodec.BD_SEQ, SparkplugDataType.INT64, bdSeq))
                .addMetrics(PayloadCodec.metric("Node Control/Rebirth", SparkplugDataType.BOOLEAN, false))
                .addMetrics(PayloadCodec.metric("Properties/Hardware", SparkplugDataType.STRING, "Raspberry Pi 5"))
                .addMetrics(PayloadCodec.metric("Properties/FW", SparkplugDataType.STRING, "v1.2.3"))
                .build();
        publish(SparkplugTopic.node(groupId, "NBIRTH", nodeId), birth);
    }

    /** Publish an NDATA update — caller supplies one or more changed metrics. */
    public void publishData(Payload.Metric... metrics) throws MqttException {
        Payload.Builder b = PayloadCodec.newPayload(nextSeq());
        for (var m : metrics) b.addMetrics(m);
        publish(SparkplugTopic.node(groupId, "NDATA", nodeId), b.build());
    }

    /** Publish DBIRTH for a child device of this edge node. */
    public void deviceBirth(String deviceId, Payload.Metric... metrics) throws MqttException {
        Payload.Builder b = PayloadCodec.newPayload(nextSeq());
        for (var m : metrics) b.addMetrics(m);
        publish(SparkplugTopic.device(groupId, "DBIRTH", nodeId, deviceId), b.build());
    }

    public void deviceData(String deviceId, Payload.Metric... metrics) throws MqttException {
        Payload.Builder b = PayloadCodec.newPayload(nextSeq());
        for (var m : metrics) b.addMetrics(m);
        publish(SparkplugTopic.device(groupId, "DDATA", nodeId, deviceId), b.build());
    }

    /** Graceful shutdown: publish NDEATH ourselves, then disconnect. */
    public void offline() throws MqttException {
        Payload death = PayloadCodec.newPayload(nextSeq())
                .addMetrics(PayloadCodec.metric(PayloadCodec.BD_SEQ, SparkplugDataType.INT64, bdSeq))
                .build();
        publish(SparkplugTopic.node(groupId, "NDEATH", nodeId), death);
        client.disconnect().waitForCompletion(2000);
        log.info("edge node {}/{} offline", groupId, nodeId);
    }

    private long nextSeq() {
        // Sparkplug seq is uint64 on the wire but cycles 0..255 logically per spec 5.6.1
        return seq.getAndUpdate(s -> (s + 1) & 0xFF);
    }

    private void publish(SparkplugTopic topic, Payload payload) throws MqttException {
        MqttMessage m = new MqttMessage(payload.toByteArray());
        m.setQos(0);          // Sparkplug B requires QoS 0 for *DATA, QoS 1 for *BIRTH/*DEATH per spec 5.x
        if (topic.msgType().endsWith("BIRTH") || topic.msgType().endsWith("DEATH")) {
            m.setQos(1);
        }
        m.setRetained(false);
        client.publish(topic.render(), m);
    }

    public String groupId() { return groupId; }
    public String nodeId() { return nodeId; }
    public long bdSeq() { return bdSeq; }

    /** Expose the underlying Paho client so demo code can yank the connection ungracefully. */
    public MqttAsyncClient client() { return client; }
}
