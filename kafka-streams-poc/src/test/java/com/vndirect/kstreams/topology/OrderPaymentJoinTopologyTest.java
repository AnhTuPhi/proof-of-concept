package com.vndirect.kstreams.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.CompletedOrder;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.PaymentEvent;
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

class OrderPaymentJoinTopologyTest {

    private TopologyTestDriver driver;
    private TestInputTopic<String, OrderEvent> ordersIn;
    private TestInputTopic<String, PaymentEvent> paymentsIn;
    private TestOutputTopic<String, CompletedOrder> completedOut;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setup() {
        AppProperties props = new AppProperties();
        var orderSerde = new JsonSerde<>(mapper, OrderEvent.class);
        var paymentSerde = new JsonSerde<>(mapper, PaymentEvent.class);
        var completedSerde = new JsonSerde<>(mapper, CompletedOrder.class);

        StreamsBuilder builder = new StreamsBuilder();
        new OrderPaymentJoinTopology(props, orderSerde, paymentSerde, completedSerde)
                .build(builder);
        Topology topology = builder.build();

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-join");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

        driver = new TopologyTestDriver(topology, config);

        ordersIn = driver.createInputTopic(props.getTopics().getOrders(),
                new StringSerializer(), orderSerde.serializer());
        paymentsIn = driver.createInputTopic(props.getTopics().getPayments(),
                new StringSerializer(), paymentSerde.serializer());
        completedOut = driver.createOutputTopic(props.getTopics().getCompletedOrders(),
                new StringDeserializer(), completedSerde.deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
    }

    @Test
    void joinsOrderAndPaymentWithinWindow() {
        Instant orderedAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant paidAt = orderedAt.plusMillis(500);

        OrderEvent order = new OrderEvent("O-1", "U-1", "P-1", 2,
                new BigDecimal("100000"), orderedAt);
        ordersIn.pipeInput("O-1", order, orderedAt);

        PaymentEvent payment = new PaymentEvent("PAY-1", "O-1",
                new BigDecimal("200000"),
                PaymentEvent.PaymentMethod.CARD,
                PaymentEvent.PaymentStatus.APPROVED, paidAt);
        paymentsIn.pipeInput("PAY-1", payment, paidAt);

        CompletedOrder result = completedOut.readValue();
        assertThat(result.orderId()).isEqualTo("O-1");
        assertThat(result.orderAmount()).isEqualByComparingTo("200000");
        assertThat(result.paidAmount()).isEqualByComparingTo("200000");
        assertThat(result.paymentStatus()).isEqualTo(PaymentEvent.PaymentStatus.APPROVED);
        assertThat(result.latencyMs()).isEqualTo(500);
    }

    @Test
    void joinIsSymmetricWhenPaymentArrivesFirst() {
        // Stream-stream join is symmetric within the window — payment arriving
        // before order still produces a single completed-order record.
        Instant orderedAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant paidAt = orderedAt.plusMillis(100);

        PaymentEvent payment = new PaymentEvent("PAY-2", "O-2",
                new BigDecimal("50000"),
                PaymentEvent.PaymentMethod.EWALLET,
                PaymentEvent.PaymentStatus.APPROVED, paidAt);
        paymentsIn.pipeInput("PAY-2", payment, paidAt);

        OrderEvent order = new OrderEvent("O-2", "U-2", "P-2", 1,
                new BigDecimal("50000"), orderedAt);
        ordersIn.pipeInput("O-2", order, orderedAt);

        CompletedOrder result = completedOut.readValue();
        assertThat(result.orderId()).isEqualTo("O-2");
        assertThat(result.paymentMethod()).isEqualTo(PaymentEvent.PaymentMethod.EWALLET);
        assertThat(completedOut.isEmpty()).isTrue();
    }

    @Test
    void dropsPaymentArrivingAfterJoinWindowExpires() {
        Instant orderedAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant lateAt = orderedAt.plusSeconds(60 * 30); // 30 minutes later

        OrderEvent order = new OrderEvent("O-3", "U-3", "P-3", 1,
                new BigDecimal("10000"), orderedAt);
        ordersIn.pipeInput("O-3", order, orderedAt);

        PaymentEvent payment = new PaymentEvent("PAY-3", "O-3",
                new BigDecimal("10000"),
                PaymentEvent.PaymentMethod.CARD,
                PaymentEvent.PaymentStatus.APPROVED, lateAt);
        paymentsIn.pipeInput("PAY-3", payment, lateAt);

        assertThat(completedOut.isEmpty())
                .as("Payment outside 10-minute window must not join")
                .isTrue();
    }
}
