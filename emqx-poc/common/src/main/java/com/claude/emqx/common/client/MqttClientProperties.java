package com.claude.emqx.common.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared MQTT connection properties resolved from each POC's application.yml.
 *
 * Property prefix: {@code mqtt}.
 *
 * <p>We use a record because Spring Boot 3 supports {@code @ConfigurationProperties}
 * binding to records natively, which makes these values explicitly immutable -
 * useful because every POC injects this and we never want it mutated at runtime.
 */
@ConfigurationProperties("mqtt")
public record MqttClientProperties(
        String brokerUrl,           // tcp://localhost:1883 or ssl://...:8883 or ws://
        String clientIdPrefix,      // appended with a random suffix unless cleanSession=false
        String username,
        String password,
        boolean cleanSession,       // MQTT 3 flag (POC 09)
        long sessionExpiryInterval, // MQTT 5 equivalent in seconds (POC 09)
        int keepAliveSeconds,       // tune low for fast device-offline detection, see POC 08
        int connectionTimeoutSeconds,
        int maxInflight,
        boolean automaticReconnect, // see POC 02 for why we usually want our OWN reconnect
        Tls tls
) {
    public record Tls(
            boolean enabled,
            String caCertPath,      // PEM
            String clientCertPath,  // PEM
            String clientKeyPath,   // PEM (PKCS#8)
            boolean verifyHostname
    ) {}
}
