package com.vndirect.kstreams.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.EnrichedOrder;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import com.vndirect.kstreams.serdes.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEnrichmentTopologyTest {

    private TopologyTestDriver driver;
    private TestInputTopic<String, OrderEvent> ordersIn;
    private TestInputTopic<String, Product> productsIn;
    private TestInputTopic<String, User> usersIn;
    private TestOutputTopic<String, EnrichedOrder> enrichedOut;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setup() {
        AppProperties props = new AppProperties();
        var orderSerde = new JsonSerde<>(mapper, OrderEvent.class);
        var productSerde = new JsonSerde<>(mapper, Product.class);
        var userSerde = new JsonSerde<>(mapper, User.class);
        var enrichedSerde = new JsonSerde<>(mapper, EnrichedOrder.class);

        StreamsBuilder builder = new StreamsBuilder();
        new OrderEnrichmentTopology(props, orderSerde, productSerde, userSerde, enrichedSerde)
                .build(builder);
        Topology topology = builder.build();

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-enrichment");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

        driver = new TopologyTestDriver(topology, config);

        ordersIn = driver.createInputTopic(props.getTopics().getOrders(),
                new StringSerializer(), orderSerde.serializer());
        productsIn = driver.createInputTopic(props.getTopics().getProducts(),
                new StringSerializer(), productSerde.serializer());
        usersIn = driver.createInputTopic(props.getTopics().getUsers(),
                new StringSerializer(), userSerde.serializer());
        enrichedOut = driver.createOutputTopic(props.getTopics().getEnrichedOrders(),
                new StringDeserializer(), enrichedSerde.deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
    }

    @Test
    void enrichesOrderWithProductAndUserMetadata() {
        productsIn.pipeInput("P-001", new Product("P-001", "VND-Equity", "EQUITY", new BigDecimal("150000")));
        usersIn.pipeInput("U-1001", new User("U-1001", "Nguyen A", "GOLD", "VN"));

        OrderEvent order = new OrderEvent("O-1", "U-1001", "P-001", 2,
                new BigDecimal("150000"), Instant.parse("2026-06-01T10:00:00Z"));
        ordersIn.pipeInput("O-1", order);

        EnrichedOrder result = enrichedOut.readValue();
        assertThat(result.orderId()).isEqualTo("O-1");
        assertThat(result.productName()).isEqualTo("VND-Equity");
        assertThat(result.category()).isEqualTo("EQUITY");
        assertThat(result.userDisplayName()).isEqualTo("Nguyen A");
        assertThat(result.userTier()).isEqualTo("GOLD");
        assertThat(result.country()).isEqualTo("VN");
        assertThat(result.totalAmount()).isEqualByComparingTo("300000");
    }

    @Test
    void fillsDefaultsWhenProductMissing() {
        usersIn.pipeInput("U-1001", new User("U-1001", "Nguyen A", "GOLD", "VN"));

        OrderEvent order = new OrderEvent("O-2", "U-1001", "P-UNKNOWN", 1,
                new BigDecimal("99000"), Instant.now());
        ordersIn.pipeInput("O-2", order);

        EnrichedOrder result = enrichedOut.readValue();
        assertThat(result.productName()).isEqualTo("UNKNOWN");
        assertThat(result.category()).isEqualTo("UNCATEGORIZED");
        assertThat(result.userDisplayName()).isEqualTo("Nguyen A");
    }

    @Test
    void fillsDefaultsWhenUserMissing() {
        productsIn.pipeInput("P-001", new Product("P-001", "VND-Equity", "EQUITY", new BigDecimal("150000")));

        OrderEvent order = new OrderEvent("O-3", "U-GHOST", "P-001", 1,
                new BigDecimal("150000"), Instant.now());
        ordersIn.pipeInput("O-3", order);

        EnrichedOrder result = enrichedOut.readValue();
        assertThat(result.userDisplayName()).isEqualTo("UNKNOWN");
        assertThat(result.userTier()).isEqualTo("UNKNOWN");
        assertThat(result.country()).isEqualTo("UNKNOWN");
        assertThat(result.category()).isEqualTo("EQUITY");
    }
}
