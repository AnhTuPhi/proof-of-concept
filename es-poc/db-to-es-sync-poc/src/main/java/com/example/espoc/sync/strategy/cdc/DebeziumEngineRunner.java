package com.example.espoc.sync.strategy.cdc;

import com.example.espoc.sync.es.ProductEsIndexer;
import com.example.espoc.sync.model.dto.ProductDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the Debezium embedded engine in a single background thread.
 * It tails the Postgres WAL via the {@code pgoutput} plugin and routes each row change to ES.
 *
 * <p>Notes on the production version:
 * <ul>
 *   <li>Embedded engine has a single thread of execution and stores offsets in a local file.
 *       For HA / multi-instance, use Debezium via Kafka Connect instead.</li>
 *   <li>{@code REPLICA IDENTITY FULL} on the source table is required so DELETEs carry old-row data.</li>
 *   <li>Logical replication slot accumulates WAL when the engine is stopped — keep that in mind.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(value = "app.sync.cdc.enabled", havingValue = "true", matchIfMissing = true)
public class DebeziumEngineRunner {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineRunner.class);

    private final ProductEsIndexer indexer;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debezium-engine");
        t.setDaemon(true);
        return t;
    });

    @Value("${spring.datasource.url}") private String jdbcUrl;
    @Value("${spring.datasource.username}") private String dbUser;
    @Value("${spring.datasource.password}") private String dbPassword;
    @Value("${app.sync.cdc.offset-storage-file}") private String offsetFile;
    @Value("${app.sync.cdc.slot-name}") private String slotName;
    @Value("${app.sync.cdc.publication-name}") private String publicationName;

    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private final AtomicLong eventsProcessed = new AtomicLong();
    private volatile String lastLsn = "n/a";

    public DebeziumEngineRunner(ProductEsIndexer indexer, ObjectMapper mapper) {
        this.indexer = indexer;
        this.mapper = mapper;
    }

    @PostConstruct
    public void start() throws IOException {
        Path offsetPath = Path.of(offsetFile).toAbsolutePath();
        Files.createDirectories(offsetPath.getParent());

        // Parse JDBC URL: jdbc:postgresql://host:port/db?params
        String hostPort = jdbcUrl.substring("jdbc:postgresql://".length()).split("/", 2)[0];
        String dbName = jdbcUrl.substring("jdbc:postgresql://".length()).split("/", 2)[1].split("\\?")[0];
        String host = hostPort.split(":")[0];
        int port = hostPort.contains(":") ? Integer.parseInt(hostPort.split(":")[1]) : 5432;

        Properties p = new Properties();
        p.setProperty("name", "espoc-sync-cdc");
        p.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        p.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        p.setProperty("offset.storage.file.filename", offsetPath.toString());
        p.setProperty("offset.flush.interval.ms", "1000");
        p.setProperty("database.hostname", host);
        p.setProperty("database.port", Integer.toString(port));
        p.setProperty("database.user", dbUser);
        p.setProperty("database.password", dbPassword);
        p.setProperty("database.dbname", dbName);
        p.setProperty("database.server.name", "espoc-pg");
        p.setProperty("topic.prefix", "espoc.cdc");                 // required in Debezium 2.x
        p.setProperty("plugin.name", "pgoutput");
        p.setProperty("slot.name", slotName);
        p.setProperty("publication.name", publicationName);
        p.setProperty("schema.include.list", "sync_cdc");
        p.setProperty("table.include.list", "sync_cdc.products");
        p.setProperty("snapshot.mode", "initial");

        engine = DebeziumEngine.create(Json.class)
                .using(p)
                .notifying(this::handleEvent)
                .build();
        executor.submit(engine);
        log.info("Debezium engine started — slot={}, publication={}", slotName, publicationName);
    }

    private void handleEvent(ChangeEvent<String, String> rec) {
        try {
            if (rec.value() == null) {
                // tombstone — skip; we react to the DELETE event itself.
                return;
            }
            JsonNode value = mapper.readTree(rec.value());
            JsonNode payload = value.has("payload") ? value.get("payload") : value;
            String op = payload.path("op").asText();              // c, u, d, r (snapshot)
            JsonNode after = payload.get("after");
            JsonNode before = payload.get("before");

            switch (op) {
                case "c", "u", "r" -> {
                    if (after == null || after.isNull()) return;
                    ProductDto dto = toDto(after);
                    indexer.index(ProductEsIndexer.IDX_CDC, dto);
                }
                case "d" -> {
                    if (before == null || before.isNull()) return;
                    String id = before.get("id").asText();
                    indexer.delete(ProductEsIndexer.IDX_CDC, id);
                }
                default -> log.debug("Skipping unhandled op '{}': {}", op, rec.value());
            }
            long n = eventsProcessed.incrementAndGet();
            if (payload.has("source") && payload.get("source").has("lsn")) {
                lastLsn = payload.get("source").get("lsn").asText();
            }
            if (n % 100 == 0) log.info("CDC: processed {} events (last lsn={})", n, lastLsn);
        } catch (Exception e) {
            log.error("CDC handler failed for record: {}", rec.value(), e);
            // Engine continues — for at-least-once you'd want a DLQ here.
        }
    }

    private ProductDto toDto(JsonNode after) {
        return new ProductDto(
                after.get("id").asText(),
                after.get("sku").asText(),
                after.get("name").asText(),
                after.path("description").asText(null),
                after.get("price_cents").asLong(),
                after.get("stock").asInt(),
                after.path("version").asLong(0),
                parseInstant(after.path("created_at")),
                parseInstant(after.path("updated_at")));
    }

    private Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull()) return null;
        // Debezium emits TIMESTAMP WITH TIME ZONE columns as microsecond epoch by default for non-precise mode.
        if (node.isNumber()) return Instant.ofEpochMilli(node.asLong() / 1000L);
        return Instant.parse(node.asText());
    }

    public long eventsProcessed() { return eventsProcessed.get(); }
    public String lastLsn() { return lastLsn; }

    @PreDestroy
    public void stop() {
        try {
            if (engine != null) engine.close();
            executor.shutdownNow();
            log.info("Debezium engine stopped — processed {} events total", eventsProcessed.get());
        } catch (IOException e) {
            log.warn("Error stopping Debezium engine", e);
        }
    }
}
