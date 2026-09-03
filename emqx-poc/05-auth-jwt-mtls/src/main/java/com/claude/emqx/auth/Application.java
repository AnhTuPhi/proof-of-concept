package com.claude.emqx.auth;

import com.claude.emqx.common.client.MqttClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * POC 05 - Auth.
 *
 * <p>Three auth modes wired into EMQX in this POC:
 * <ol>
 *   <li><b>JWT (HMAC-SHA256)</b> - this Spring app is the issuer.
 *       Devices call /jwt/issue, get a token, and pass it as the MQTT password.</li>
 *   <li><b>HTTP auth backend</b> - EMQX calls back to this Spring app on every
 *       CONNECT to /auth/mqtt for an allow/deny decision. Useful when your
 *       device identity lives in a system EMQX can't reach directly.</li>
 *   <li><b>mTLS</b> - this app generates a CA + per-device cert into /tmp,
 *       and the device uses the client cert during the TLS handshake. The
 *       CN becomes the MQTT username at the broker.</li>
 * </ol>
 *
 * <p>The auth modes are <b>complementary</b>, not exclusive. Real deployments
 * pick 1-2: most pick mTLS + JWT (cert identifies the device, JWT carries
 * tenant claims) or mTLS alone (cert CN is the identity, no JWT needed).
 */
@SpringBootApplication
@EnableConfigurationProperties(MqttClientProperties.class)
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
