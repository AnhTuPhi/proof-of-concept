package com.claude.emqx.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * POC 06 - EMQX Rule Engine + Kafka & Postgres Bridges.
 *
 * <p>This app does three things:
 * <ol>
 *   <li>Provisions EMQX rules + connectors via the Mgmt API at startup.
 *       Configuration-as-code beats clicking through the dashboard.</li>
 *   <li>Consumes the Kafka topic the rule writes to, so you can SEE messages
 *       arrive end-to-end (device PUBLISH -> EMQX rule -> Kafka -> this app).</li>
 *   <li>Exposes a /verify endpoint that counts rows in the Postgres telemetry
 *       table to confirm the Postgres sink works.</li>
 * </ol>
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
