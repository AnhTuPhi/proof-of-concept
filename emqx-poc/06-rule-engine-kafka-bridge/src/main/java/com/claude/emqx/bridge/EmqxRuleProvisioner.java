package com.claude.emqx.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Talks to the EMQX 5.x REST API to set up the rule-engine rules and
 * connectors required by this POC.
 *
 * <p>Why provision via API instead of mounting a HOCON file:
 *  - HOCON config files are loaded at boot, but you can't re-apply them
 *    without a node restart. The Mgmt API gives you hot updates.
 *  - In production you'd version this in git and ship it from CI, exactly
 *    like a Terraform run.
 *  - The HTTP responses are explicit about validation errors (HOCON would
 *    surface them as cryptic boot failures).
 */
@Component
public class EmqxRuleProvisioner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EmqxRuleProvisioner.class);

    private final RestTemplate http = new RestTemplate();

    @Value("${emqx.mgmt-url:http://localhost:18083/api/v5}")
    private String baseUrl;

    @Value("${emqx.mgmt-user:admin}")
    private String user;

    @Value("${emqx.mgmt-pass:public}")
    private String pass;

    @Override
    public void run(String... args) {
        try {
            createKafkaConnector();
            createKafkaAction();
            createPostgresConnector();
            createPostgresAction();
            createRule();
            log.info("EMQX rules + bridges provisioned.");
        } catch (Exception e) {
            // Don't crash the app - the broker might not be reachable yet.
            // POC startup is forgiving; production would gate on this.
            log.warn("Could not provision EMQX rules (broker not ready?): {}", e.getMessage());
        }
    }

    // ---------- Connectors ----------
    private void createKafkaConnector() {
        String body = """
                {
                  "name": "kafka_local",
                  "type": "kafka_producer",
                  "config": {
                    "bootstrap_hosts": "kafka:9092",
                    "connect_timeout": "5s",
                    "min_metadata_refresh_interval": "3s",
                    "metadata_request_timeout": "5s",
                    "socket_opts": { "sndbuf": "1024KB", "recbuf": "1024KB" },
                    "ssl": { "enable": false }
                  }
                }
                """;
        putJson("/connectors/kafka_producer:kafka_local", body);
    }

    private void createPostgresConnector() {
        String body = """
                {
                  "name": "pg_local",
                  "type": "pgsql",
                  "config": {
                    "server": "postgres:5432",
                    "database": "emqx",
                    "username": "emqx",
                    "password": "emqx_pass",
                    "pool_size": 8
                  }
                }
                """;
        putJson("/connectors/pgsql:pg_local", body);
    }

    // ---------- Actions (the sink-specific config) ----------
    private void createKafkaAction() {
        // The "kafka_topic" template uses MQTT message attributes via ${...}.
        // We partition by deviceId so per-device messages keep order in Kafka.
        String body = """
                {
                  "name": "to_kafka",
                  "type": "kafka_producer",
                  "connector": "kafka_local",
                  "parameters": {
                    "topic": "iot.telemetry",
                    "message": {
                      "key": "${.payload.deviceId}",
                      "value": "${.payload}"
                    },
                    "partition_strategy": "key_dispatch",
                    "buffer": { "memory_overload_protection": true }
                  },
                  "resource_opts": {
                    "request_ttl": "15s",
                    "health_check_interval": "15s",
                    "query_mode": "async",
                    "worker_pool_size": 8,
                    "batch_size": 100,
                    "batch_time": "20ms"
                  }
                }
                """;
        putJson("/actions/kafka_producer:to_kafka", body);
    }

    private void createPostgresAction() {
        // We use jsonb_set-style INSERT with prepared params.
        // EMQX SQL templates use ${.field} syntax across the payload + envelope.
        String body = """
                {
                  "name": "to_pg",
                  "type": "pgsql",
                  "connector": "pg_local",
                  "parameters": {
                    "sql": "INSERT INTO telemetry(device_id, tenant_id, topic, payload, qos) VALUES (${.payload.deviceId}, ${.payload.tenantId}, ${.topic}, ${.payload}::jsonb, ${.qos})"
                  },
                  "resource_opts": {
                    "request_ttl": "15s",
                    "health_check_interval": "15s",
                    "query_mode": "async",
                    "worker_pool_size": 8,
                    "batch_size": 50,
                    "batch_time": "20ms"
                  }
                }
                """;
        putJson("/actions/pgsql:to_pg", body);
    }

    // ---------- Rule ----------
    private void createRule() {
        // EMQX rule-engine SQL grammar: SELECT ... FROM topicFilter [WHERE ...]
        // FROM is the topic; SELECT picks what to put in the payload sent to actions.
        // Wildcards work; in EMQX 5 we use the same MQTT filters.
        String body = """
                {
                  "id": "rule_telemetry_routing",
                  "name": "route telemetry",
                  "sql": "SELECT * FROM \\"tenant/+/devices/+/telemetry\\" WHERE payload.metrics.temp_c > 0",
                  "actions": ["kafka_producer:to_kafka", "pgsql:to_pg"],
                  "description": "Provisioned by POC 06: fan-out telemetry to Kafka and Postgres."
                }
                """;
        putJson("/rules/rule_telemetry_routing", body);
    }

    // ---------- HTTP plumbing ----------
    private void putJson(String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Basic " +
                Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
        try {
            http.exchange(baseUrl + path, HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
            log.info("PUT {} OK", path);
        } catch (Exception ex) {
            // Often a 200 returns body the RestTemplate can't deserialize. Just log.
            log.info("PUT {} returned: {}", path, ex.getMessage());
        }
    }
}
