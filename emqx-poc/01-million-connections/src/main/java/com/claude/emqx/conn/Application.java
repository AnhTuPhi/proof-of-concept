package com.claude.emqx.conn;

import com.claude.emqx.common.client.MqttClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * POC 01: Million Connections.
 *
 * <p>This app spins up many lightweight MQTT clients from a single JVM (typically
 * 100k+ on a tuned 8-core box; the broker side can handle 1M+). It demonstrates:
 *
 * <ol>
 *   <li><b>Why HiveMQ client over Paho here:</b> HiveMQ uses Netty internally
 *       (one I/O thread shared across clients), so 100k connections cost
 *       memory ~= 100k * 16KB ~= 1.6GB. Paho creates a thread per connection,
 *       which dies at 5-10k.</li>
 *   <li><b>OS tuning required:</b> {@code ulimit -n 1048576}, sysctl
 *       {@code net.ipv4.ip_local_port_range="1024 65535"} (client-side ephemeral
 *       port exhaustion is the #1 wall), TCP buffers.</li>
 *   <li><b>JVM tuning:</b> {@code -Xmx} sized for ~16KB/conn,
 *       {@code -XX:MaxDirectMemorySize} for Netty buffers, and crucially
 *       {@code -Djdk.nio.maxCachedBufferSize=262144} so per-thread NIO buffer
 *       caches don't bloat.</li>
 *   <li><b>Broker-side Erlang VM tuning:</b> async threads, scheduler binding,
 *       documented in the README.</li>
 * </ol>
 *
 * <p>Endpoints exposed on :8101 - see {@link ConnectionFleetController}.
 */
@SpringBootApplication
@EnableConfigurationProperties(MqttClientProperties.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
