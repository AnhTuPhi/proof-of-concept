package com.claude.emqx.common.client;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/**
 * Factory that builds a configured Paho MQTT v5 async client.
 *
 * <p>This is a thin factory rather than a Spring bean because several POCs need
 * to spin up <em>many</em> clients (POC 01 builds 100k+ from one JVM, POC 04
 * builds a consumer group of N members). Having a stateless factory keeps that
 * possible without juggling Spring scopes.
 *
 * <p>Why Paho v5 and not v3:
 * <ul>
 *   <li>POC 07 needs reason codes, user properties, topic alias, session
 *       expiry - all MQTT 5 features.</li>
 *   <li>EMQX brokers default to MQTT 5; downgrade is automatic for v3 clients.</li>
 *   <li>The v5 API is slightly different (no separate sync client) but maps
 *       cleanly to async patterns the JVM is good at.</li>
 * </ul>
 */
public final class PahoMqtt5ClientFactory {

    private static final Logger log = LoggerFactory.getLogger(PahoMqtt5ClientFactory.class);

    private final MqttClientProperties props;

    public PahoMqtt5ClientFactory(MqttClientProperties props) {
        this.props = props;
    }

    /**
     * Build a connected client.
     *
     * @param clientIdSuffix appended to {@link MqttClientProperties#clientIdPrefix()};
     *                       passing {@code null} generates a random UUID. Using a
     *                       stable suffix is REQUIRED if {@code cleanSession=false}
     *                       (otherwise the broker creates a fresh session each connect
     *                       and you accumulate orphan sessions - see POC 09).
     */
    public MqttAsyncClient build(String clientIdSuffix, MqttCallback callback) throws MqttException {
        String suffix = clientIdSuffix != null ? clientIdSuffix : UUID.randomUUID().toString();
        String clientId = props.clientIdPrefix() + "-" + suffix;

        // MemoryPersistence is fine for QoS>0 because for production-grade durability
        // you'd use MqttDefaultFilePersistence, but in our POCs the message volume per
        // client is bounded and we'd rather not write disk.
        MqttAsyncClient client = new MqttAsyncClient(
                props.brokerUrl(),
                clientId,
                new MemoryPersistence()
        );

        if (callback != null) {
            client.setCallback(callback);
        }

        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setUserName(props.username());
        opts.setPassword(props.password() == null ? null : props.password().getBytes(StandardCharsets.UTF_8));
        opts.setCleanStart(props.cleanSession());                 // MQTT 5 calls this cleanStart
        opts.setSessionExpiryInterval(props.sessionExpiryInterval());
        opts.setKeepAliveInterval(props.keepAliveSeconds());
        opts.setConnectionTimeout(props.connectionTimeoutSeconds());
        opts.setAutomaticReconnect(props.automaticReconnect());
        opts.setMaxReconnectDelay(60_000);
        // Paho v5 internally caps inflight; this is the public knob:
        // MQTT 5 spec: receiveMaximum from the client side.
        opts.setReceiveMaximum(props.maxInflight());

        if (props.tls() != null && props.tls().enabled()) {
            SSLSocketFactory ssl = TlsSocketFactoryBuilder.build(props.tls());
            opts.setSocketFactory(ssl);
            opts.setHttpsHostnameVerificationEnabled(props.tls().verifyHostname());
        }

        log.info("MQTT connect clientId={} broker={} cleanSession={} sessionExpiry={}s",
                clientId, props.brokerUrl(), props.cleanSession(), props.sessionExpiryInterval());

        IMqttToken token = client.connect(opts);
        token.waitForCompletion(props.connectionTimeoutSeconds() * 1000L);
        return client;
    }

    /**
     * Default callback that just logs - POCs subclass or pass their own.
     */
    public static MqttCallback loggingCallback(String name) {
        return new MqttCallback() {
            @Override public void disconnected(MqttDisconnectResponse r) {
                log.info("[{}] disconnected: reasonCode={} reason={}", name,
                        r.getReturnCode(), r.getReasonString());
            }
            @Override public void mqttErrorOccurred(MqttException ex) {
                log.warn("[{}] mqttError: {}", name, ex.getMessage());
            }
            @Override public void messageArrived(String topic, MqttMessage msg) {
                log.debug("[{}] msg on {} qos={} payload-len={}", name, topic, msg.getQos(), msg.getPayload().length);
            }
            @Override public void deliveryComplete(IMqttToken t) {}
            @Override public void connectComplete(boolean reconnect, String uri) {
                log.info("[{}] connect-complete reconnect={} uri={}", name, reconnect, uri);
            }
            @Override public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        };
    }
}
