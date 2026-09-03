package com.claude.emqx.qos;

import com.claude.emqx.common.client.MqttClientProperties;
import com.claude.emqx.common.client.PahoMqtt5ClientFactory;
import com.claude.emqx.common.util.Json;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Run identical workloads at QoS 0/1/2, capture per-level throughput and
 * end-to-end latency, and detect the duplicates that QoS 1 leaks (and that
 * QoS 2 should never leak).
 *
 * <p>Key insight you can demonstrate live:
 * <pre>
 *   QoS 0: PUBLISH                             (1 hop)
 *   QoS 1: PUBLISH -> PUBACK                   (2 hops; receiver MAY get dup)
 *   QoS 2: PUBLISH -> PUBREC -> PUBREL -> PUBCOMP (4 hops, exactly-once)
 * </pre>
 *
 * <p>QoS 2 is the one teams pick "to be safe" and tank their throughput.
 * Showing the actual cost in /benchmark output makes that choice less reflexive.
 */
@Service
public class QosBenchmark {

    private static final Logger log = LoggerFactory.getLogger(QosBenchmark.class);

    private final PahoMqtt5ClientFactory factory;
    private final MqttClientProperties props;

    public QosBenchmark(MqttClientProperties props) {
        this.props = props;
        this.factory = new PahoMqtt5ClientFactory(props);
    }

    public Result run(int qos, int messageCount, int payloadBytes) throws Exception {
        if (qos < 0 || qos > 2) throw new IllegalArgumentException("qos must be 0..2");
        String topic = "qos-test/" + qos + "/" + UUID.randomUUID();
        byte[] payload = new byte[payloadBytes];
        java.util.Arrays.fill(payload, (byte) 'x');

        Map<Long, Integer> deliveredBySeq = new ConcurrentHashMap<>();
        Map<Long, Long> sentAtBySeq = new ConcurrentHashMap<>();
        LongAdder duplicates = new LongAdder();
        LongAdder latencySumNs = new LongAdder();

        // ---- Subscriber ----
        MqttAsyncClient sub = factory.build("sub-" + qos + "-" + UUID.randomUUID(), new MqttCallback() {
            @Override public void messageArrived(String t, MqttMessage msg) {
                long seq = readSeq(msg.getPayload());
                Long sentAt = sentAtBySeq.get(seq);
                if (sentAt != null) {
                    latencySumNs.add(System.nanoTime() - sentAt);
                }
                Integer prev = deliveredBySeq.put(seq, 1);
                if (prev != null) duplicates.increment();
            }
            @Override public void disconnected(MqttDisconnectResponse r) {}
            @Override public void mqttErrorOccurred(MqttException ex) {}
            @Override public void deliveryComplete(org.eclipse.paho.mqttv5.client.IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {}
            @Override public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        sub.subscribe(topic, qos).waitForCompletion(5000);

        // ---- Publisher ----
        MqttAsyncClient pub = factory.build("pub-" + qos + "-" + UUID.randomUUID(),
                PahoMqtt5ClientFactory.loggingCallback("pub"));

        long startNs = System.nanoTime();
        for (long i = 0; i < messageCount; i++) {
            byte[] body = withSeq(payload, i);
            sentAtBySeq.put(i, System.nanoTime());
            MqttMessage m = new MqttMessage(body);
            m.setQos(qos);
            pub.publish(topic, m);     // do NOT wait per message - we want to measure throughput
        }
        // Now wait for QoS handshakes to complete. For QoS 0 this returns immediately.
        // We approximate completion by polling deliveredBySeq.size() until it stabilizes.
        long deadline = System.currentTimeMillis() + 30_000;
        while (deliveredBySeq.size() < messageCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long elapsedNs = System.nanoTime() - startNs;

        long delivered = deliveredBySeq.size();
        long lost = messageCount - delivered;
        double avgLatencyMs = delivered == 0 ? 0 : latencySumNs.sum() / (double) delivered / 1_000_000.0;
        double throughputMsgsPerSec = messageCount * 1_000_000_000.0 / elapsedNs;

        pub.disconnect();
        sub.disconnect();

        Result r = new Result(qos, messageCount, payloadBytes, delivered, lost, duplicates.sum(),
                avgLatencyMs, throughputMsgsPerSec);
        log.info("QoS {} result: {}", qos, r);
        return r;
    }

    /** Run all three levels with the same params; useful for chart-friendly output. */
    public Map<String, Result> compareAll(int messageCount, int payloadBytes) throws Exception {
        Map<String, Result> out = new HashMap<>();
        for (int q : new int[]{0, 1, 2}) {
            out.put("qos" + q, run(q, messageCount, payloadBytes));
        }
        return out;
    }

    private byte[] withSeq(byte[] payload, long seq) {
        byte[] body = new byte[payload.length];
        System.arraycopy(payload, 0, body, 0, payload.length);
        // first 8 bytes = sequence number (big-endian)
        for (int i = 7; i >= 0; i--) { body[i] = (byte) (seq & 0xff); seq >>>= 8; }
        return body;
    }

    private long readSeq(byte[] body) {
        long s = 0;
        for (int i = 0; i < 8; i++) { s = (s << 8) | (body[i] & 0xff); }
        return s;
    }

    public record Result(int qos, int sent, int payloadBytes, long delivered, long lost, long duplicates,
                         double avgLatencyMs, double throughputMsgsPerSec) {
        @Override public String toString() {
            return new String(Json.toBytes(this));
        }
    }
}
