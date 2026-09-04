package com.vndirect.kstreams.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.CategoryRevenue;
import com.vndirect.kstreams.model.EnrichedOrder;
import com.vndirect.kstreams.model.UserOrderStats;
import com.vndirect.kstreams.serdes.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class WindowedAggregationsTopologyTest {

    private TopologyTestDriver driver;
    private TestInputTopic<String, EnrichedOrder> enrichedIn;
    private TestOutputTopic<String, CategoryRevenue> revenueOut;
    private TestOutputTopic<String, UserOrderStats> userCountsOut;
    private TestOutputTopic<String, UserOrderStats> sessionsOut;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AppProperties props = new AppProperties();

    @BeforeEach
    void setup() {
        var enrichedSerde = new JsonSerde<>(mapper, EnrichedOrder.class);
        var revenueSerde = new JsonSerde<>(mapper, CategoryRevenue.class);
        var statsSerde = new JsonSerde<>(mapper, UserOrderStats.class);

        StreamsBuilder builder = new StreamsBuilder();
        // Bridge: read from the same topic the aggregations consume
        KStream<String, EnrichedOrder> enriched = builder.stream(
                props.getTopics().getEnrichedOrders(),
                Consumed.with(Serdes.String(), enrichedSerde));

        new WindowedAggregationsTopology(props, enrichedSerde, revenueSerde, statsSerde)
                .build(enriched);

        Topology topology = builder.build();
        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-aggregations");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        // Disable cache so every update is emitted — required for deterministic test output
        config.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);

        driver = new TopologyTestDriver(topology, config);

        enrichedIn = driver.createInputTopic(props.getTopics().getEnrichedOrders(),
                new StringSerializer(), enrichedSerde.serializer());
        revenueOut = driver.createOutputTopic(props.getTopics().getCategoryRevenue(),
                new StringDeserializer(), revenueSerde.deserializer());
        userCountsOut = driver.createOutputTopic(props.getTopics().getUserOrderCounts(),
                new StringDeserializer(), statsSerde.deserializer());
        sessionsOut = driver.createOutputTopic(props.getTopics().getUserSessions(),
                new StringDeserializer(), statsSerde.deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
    }

    @Test
    void tumblingWindowAggregatesRevenuePerCategory() {
        Instant base = Instant.parse("2026-06-01T10:00:00Z");
        enrichedIn.pipeInput("O-1", order("U-1", "EQUITY", new BigDecimal("100000")), base);
        enrichedIn.pipeInput("O-2", order("U-2", "EQUITY", new BigDecimal("200000")), base.plusSeconds(5));
        enrichedIn.pipeInput("O-3", order("U-1", "BOND", new BigDecimal("50000")), base.plusSeconds(10));

        // Advance past the 1-minute window + grace so the final state flushes
        driver.advanceWallClockTime(Duration.ofMinutes(2));
        enrichedIn.pipeInput("O-flush", order("U-9", "OTHER", new BigDecimal("1")),
                base.plus(Duration.ofMinutes(2)));

        List<KeyValue<String, CategoryRevenue>> results = revenueOut.readKeyValuesToList();
        assertThat(results).isNotEmpty();

        CategoryRevenue equity = results.stream()
                .filter(kv -> kv.key.equals("EQUITY"))
                .map(kv -> kv.value)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(equity.orderCount()).isEqualTo(2);
        assertThat(equity.totalRevenue()).isEqualByComparingTo("300000");
    }

    @Test
    void hoppingWindowProducesRollingUserCounts() {
        Instant base = Instant.parse("2026-06-01T10:00:00Z");
        enrichedIn.pipeInput("O-1", order("U-100", "EQUITY", new BigDecimal("100000")), base);
        enrichedIn.pipeInput("O-2", order("U-100", "BOND", new BigDecimal("50000")), base.plusSeconds(30));

        driver.advanceWallClockTime(Duration.ofMinutes(2));

        List<KeyValue<String, UserOrderStats>> results = userCountsOut.readKeyValuesToList();
        assertThat(results).isNotEmpty();
        UserOrderStats latest = results.stream()
                .filter(kv -> kv.key.equals("U-100"))
                .map(kv -> kv.value)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(latest.orderCount()).isEqualTo(2);
        assertThat(latest.totalSpent()).isEqualByComparingTo("150000");
    }

    @Test
    void sessionWindowGroupsOrdersWithinInactivityGap() {
        Instant base = Instant.parse("2026-06-01T10:00:00Z");
        enrichedIn.pipeInput("O-1", order("U-200", "EQUITY", new BigDecimal("100000")), base);
        enrichedIn.pipeInput("O-2", order("U-200", "EQUITY", new BigDecimal("50000")), base.plusSeconds(10));
        // Same session - gap < 30s
        enrichedIn.pipeInput("O-3", order("U-200", "BOND", new BigDecimal("25000")), base.plusSeconds(25));

        driver.advanceWallClockTime(Duration.ofMinutes(2));
        // Trigger window close
        enrichedIn.pipeInput("O-flush", order("U-999", "OTHER", new BigDecimal("1")),
                base.plus(Duration.ofMinutes(2)));

        List<KeyValue<String, UserOrderStats>> results = sessionsOut.readKeyValuesToList();
        UserOrderStats latest = results.stream()
                .filter(kv -> kv.key.equals("U-200"))
                .map(kv -> kv.value)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(latest.orderCount()).isEqualTo(3);
        assertThat(latest.totalSpent()).isEqualByComparingTo("175000");
    }

    private EnrichedOrder order(String userId, String category, BigDecimal amount) {
        return new EnrichedOrder("O-x", userId, "User", "GOLD", "VN",
                "P-x", "Product-X", category, 1, amount, amount, Instant.now());
    }
}
