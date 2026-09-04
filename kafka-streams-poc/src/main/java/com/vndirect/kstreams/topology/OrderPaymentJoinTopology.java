package com.vndirect.kstreams.topology;

import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.CompletedOrder;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.PaymentEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Stream-stream join: orders and payments within a sliding 10-minute window.
 * Both sides are re-keyed by orderId so join partitions line up. Output is
 * a {@link CompletedOrder} with the end-to-end latency between order and payment.
 */
@Component
public class OrderPaymentJoinTopology {

    public static final Duration JOIN_WINDOW = Duration.ofMinutes(10);
    public static final Duration JOIN_GRACE = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentJoinTopology.class);

    private final AppProperties props;
    private final Serde<OrderEvent> orderSerde;
    private final Serde<PaymentEvent> paymentSerde;
    private final Serde<CompletedOrder> completedSerde;

    public OrderPaymentJoinTopology(AppProperties props,
                                    Serde<OrderEvent> orderSerde,
                                    Serde<PaymentEvent> paymentSerde,
                                    Serde<CompletedOrder> completedSerde) {
        this.props = props;
        this.orderSerde = orderSerde;
        this.paymentSerde = paymentSerde;
        this.completedSerde = completedSerde;
    }

    public void build(StreamsBuilder builder) {
        KStream<String, OrderEvent> orders = builder.stream(
                props.getTopics().getOrders(),
                Consumed.with(Serdes.String(), orderSerde)
        ).filter((k, v) -> v != null);

        KStream<String, PaymentEvent> payments = builder.stream(
                props.getTopics().getPayments(),
                Consumed.with(Serdes.String(), paymentSerde)
        ).filter((k, v) -> v != null);

        // Payments arrive keyed by paymentId; re-key by orderId for the join
        KStream<String, PaymentEvent> paymentsByOrderId =
                payments.selectKey((k, v) -> v.orderId(),
                        org.apache.kafka.streams.kstream.Named.as("payments-by-order-id"));

        KStream<String, CompletedOrder> completed = orders.join(
                paymentsByOrderId,
                (order, payment) -> new CompletedOrder(
                        order.orderId(),
                        order.userId(),
                        order.productId(),
                        order.totalAmount(),
                        payment.amount(),
                        payment.method(),
                        payment.status(),
                        order.orderedAt(),
                        payment.paidAt(),
                        Duration.between(order.orderedAt(), payment.paidAt()).toMillis()
                ),
                JoinWindows.ofTimeDifferenceAndGrace(JOIN_WINDOW, JOIN_GRACE),
                StreamJoined.with(Serdes.String(), orderSerde, paymentSerde)
        );

        completed.peek((k, v) -> log.debug("Completed order {} latency={}ms status={}",
                v.orderId(), v.latencyMs(), v.paymentStatus()))
                .to(props.getTopics().getCompletedOrders(),
                        Produced.with(Serdes.String(), completedSerde));
    }
}
