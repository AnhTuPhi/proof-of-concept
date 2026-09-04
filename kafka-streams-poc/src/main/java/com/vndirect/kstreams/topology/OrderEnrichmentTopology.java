package com.vndirect.kstreams.topology;

import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.EnrichedOrder;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stream-table join: an OrderEvent stream is enriched with reference data
 * from compacted Product/User topics, materialized as GlobalKTables so the
 * lookups don't require co-partitioning with the orders stream.
 */
@Component
public class OrderEnrichmentTopology {

    public static final String PRODUCTS_STORE = "products-store";
    public static final String USERS_STORE = "users-store";

    private static final Logger log = LoggerFactory.getLogger(OrderEnrichmentTopology.class);

    private final AppProperties props;
    private final Serde<OrderEvent> orderSerde;
    private final Serde<Product> productSerde;
    private final Serde<User> userSerde;
    private final Serde<EnrichedOrder> enrichedSerde;

    @Autowired
    public OrderEnrichmentTopology(AppProperties props,
                                   Serde<OrderEvent> orderSerde,
                                   Serde<Product> productSerde,
                                   Serde<User> userSerde,
                                   Serde<EnrichedOrder> enrichedSerde) {
        this.props = props;
        this.orderSerde = orderSerde;
        this.productSerde = productSerde;
        this.userSerde = userSerde;
        this.enrichedSerde = enrichedSerde;
    }

    public KStream<String, EnrichedOrder> build(StreamsBuilder builder) {
        GlobalKTable<String, Product> productsTable = builder.globalTable(
                props.getTopics().getProducts(),
                Consumed.with(Serdes.String(), productSerde),
                Materialized.<String, Product>as(Stores.inMemoryKeyValueStore(PRODUCTS_STORE))
                        .withKeySerde(Serdes.String())
                        .withValueSerde(productSerde)
        );

        GlobalKTable<String, User> usersTable = builder.globalTable(
                props.getTopics().getUsers(),
                Consumed.with(Serdes.String(), userSerde),
                Materialized.<String, User>as(Stores.inMemoryKeyValueStore(USERS_STORE))
                        .withKeySerde(Serdes.String())
                        .withValueSerde(userSerde)
        );

        KStream<String, OrderEvent> orders = builder.stream(
                props.getTopics().getOrders(),
                Consumed.with(Serdes.String(), orderSerde)
        );

        KStream<String, EnrichedOrder> enriched = orders
                .filter((k, v) -> v != null,
                        org.apache.kafka.streams.kstream.Named.as("filter-null-orders"))
                .leftJoin(productsTable,
                        (orderId, order) -> order.productId(),
                        OrderEnrichmentTopology::mergeProduct)
                .leftJoin(usersTable,
                        (orderId, partial) -> partial.userId(),
                        OrderEnrichmentTopology::mergeUser);

        enriched.peek((k, v) -> log.debug("Enriched order {} for user {} category {}",
                v.orderId(), v.userId(), v.category()));

        enriched.to(props.getTopics().getEnrichedOrders(),
                Produced.with(Serdes.String(), enrichedSerde));

        return enriched;
    }

    private static EnrichedOrder mergeProduct(OrderEvent order, Product product) {
        String productName = product == null ? "UNKNOWN" : product.name();
        String category = product == null ? "UNCATEGORIZED" : product.category();
        return new EnrichedOrder(
                order.orderId(),
                order.userId(),
                null,
                null,
                null,
                order.productId(),
                productName,
                category,
                order.quantity(),
                order.unitPrice(),
                order.totalAmount(),
                order.orderedAt()
        );
    }

    private static EnrichedOrder mergeUser(EnrichedOrder partial, User user) {
        if (user == null) {
            return new EnrichedOrder(
                    partial.orderId(),
                    partial.userId(),
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    partial.productId(),
                    partial.productName(),
                    partial.category(),
                    partial.quantity(),
                    partial.unitPrice(),
                    partial.totalAmount(),
                    partial.orderedAt()
            );
        }
        return new EnrichedOrder(
                partial.orderId(),
                partial.userId(),
                user.displayName(),
                user.tier(),
                user.country(),
                partial.productId(),
                partial.productName(),
                partial.category(),
                partial.quantity(),
                partial.unitPrice(),
                partial.totalAmount(),
                partial.orderedAt()
        );
    }
}
