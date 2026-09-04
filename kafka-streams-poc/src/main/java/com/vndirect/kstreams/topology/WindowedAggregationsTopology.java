package com.vndirect.kstreams.topology;

import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.CategoryRevenue;
import com.vndirect.kstreams.model.EnrichedOrder;
import com.vndirect.kstreams.model.UserOrderStats;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Windowed aggregations over the enriched-order stream:
 *
 *   1. Tumbling 1-minute windows  → revenue per product category
 *   2. Hopping 5-min / 1-min step → rolling order counts per user
 *   3. Session windows (30s gap)  → per-user shopping sessions
 */
@Component
public class WindowedAggregationsTopology {

    public static final String CATEGORY_REVENUE_STORE = "category-revenue-store";
    public static final String USER_ORDER_COUNT_STORE = "user-order-count-store";
    public static final String USER_SESSION_STORE = "user-session-store";

    public static final Duration TUMBLING_WINDOW = Duration.ofMinutes(1);
    public static final Duration HOPPING_WINDOW = Duration.ofMinutes(5);
    public static final Duration HOPPING_ADVANCE = Duration.ofMinutes(1);
    public static final Duration SESSION_GAP = Duration.ofSeconds(30);
    public static final Duration GRACE_PERIOD = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(WindowedAggregationsTopology.class);

    private final AppProperties props;
    private final Serde<EnrichedOrder> enrichedSerde;
    private final Serde<CategoryRevenue> categoryRevenueSerde;
    private final Serde<UserOrderStats> userOrderStatsSerde;

    public WindowedAggregationsTopology(AppProperties props,
                                        Serde<EnrichedOrder> enrichedSerde,
                                        Serde<CategoryRevenue> categoryRevenueSerde,
                                        Serde<UserOrderStats> userOrderStatsSerde) {
        this.props = props;
        this.enrichedSerde = enrichedSerde;
        this.categoryRevenueSerde = categoryRevenueSerde;
        this.userOrderStatsSerde = userOrderStatsSerde;
    }

    public void build(KStream<String, EnrichedOrder> enrichedOrders) {
        buildCategoryRevenue(enrichedOrders);
        buildUserOrderCounts(enrichedOrders);
        buildUserSessions(enrichedOrders);
    }

    private void buildCategoryRevenue(KStream<String, EnrichedOrder> stream) {
        KTable<Windowed<String>, CategoryRevenue> revenueTable = stream
                .filter((k, v) -> v != null && v.category() != null)
                .selectKey((k, v) -> v.category(),
                        org.apache.kafka.streams.kstream.Named.as("rekey-by-category"))
                .groupByKey(Grouped.with(Serdes.String(), enrichedSerde))
                .windowedBy(TimeWindows.ofSizeAndGrace(TUMBLING_WINDOW, GRACE_PERIOD))
                .aggregate(
                        () -> CategoryRevenue.empty(""),
                        (category, order, agg) -> {
                            CategoryRevenue seeded = agg.category().isEmpty()
                                    ? CategoryRevenue.empty(category) : agg;
                            return seeded.accumulate(order.totalAmount());
                        },
                        Materialized.<String, CategoryRevenue,
                                org.apache.kafka.streams.state.WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>
                                as(CATEGORY_REVENUE_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(categoryRevenueSerde)
                );

        revenueTable.toStream()
                .map((windowed, value) -> {
                    CategoryRevenue stamped = value.withWindow(
                            windowed.window().start(), windowed.window().end());
                    return org.apache.kafka.streams.KeyValue.pair(windowed.key(), stamped);
                })
                .peek((k, v) -> log.debug("Category {} window [{}..{}] count={} revenue={}",
                        k, v.windowStart(), v.windowEnd(), v.orderCount(), v.totalRevenue()))
                .to(props.getTopics().getCategoryRevenue(),
                        Produced.with(Serdes.String(), categoryRevenueSerde));
    }

    private void buildUserOrderCounts(KStream<String, EnrichedOrder> stream) {
        KTable<Windowed<String>, UserOrderStats> counts = stream
                .filter((k, v) -> v != null && v.userId() != null)
                .selectKey((k, v) -> v.userId(),
                        org.apache.kafka.streams.kstream.Named.as("rekey-by-user"))
                .groupByKey(Grouped.with(Serdes.String(), enrichedSerde))
                .windowedBy(TimeWindows.ofSizeAndGrace(HOPPING_WINDOW, GRACE_PERIOD)
                        .advanceBy(HOPPING_ADVANCE))
                .aggregate(
                        () -> UserOrderStats.empty(""),
                        (userId, order, agg) -> {
                            UserOrderStats seeded = agg.userId().isEmpty()
                                    ? UserOrderStats.empty(userId) : agg;
                            return seeded.accumulate(order.totalAmount());
                        },
                        Materialized.<String, UserOrderStats,
                                org.apache.kafka.streams.state.WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>
                                as(USER_ORDER_COUNT_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(userOrderStatsSerde)
                );

        counts.toStream()
                .map((windowed, value) -> {
                    UserOrderStats stamped = value.withWindow(
                            windowed.window().start(), windowed.window().end());
                    return org.apache.kafka.streams.KeyValue.pair(windowed.key(), stamped);
                })
                .to(props.getTopics().getUserOrderCounts(),
                        Produced.with(Serdes.String(), userOrderStatsSerde));
    }

    private void buildUserSessions(KStream<String, EnrichedOrder> stream) {
        KTable<Windowed<String>, UserOrderStats> sessions = stream
                .filter((k, v) -> v != null && v.userId() != null)
                .selectKey((k, v) -> v.userId(),
                        org.apache.kafka.streams.kstream.Named.as("rekey-by-user-session"))
                .groupByKey(Grouped.with(Serdes.String(), enrichedSerde))
                .windowedBy(SessionWindows.ofInactivityGapAndGrace(SESSION_GAP, GRACE_PERIOD))
                .aggregate(
                        () -> UserOrderStats.empty(""),
                        (userId, order, agg) -> {
                            UserOrderStats seeded = agg.userId().isEmpty()
                                    ? UserOrderStats.empty(userId) : agg;
                            return seeded.accumulate(order.totalAmount());
                        },
                        (key, left, right) -> new UserOrderStats(
                                key,
                                left.orderCount() + right.orderCount(),
                                left.totalSpent().add(right.totalSpent()),
                                0L, 0L
                        ),
                        Materialized.<String, UserOrderStats,
                                org.apache.kafka.streams.state.SessionStore<org.apache.kafka.common.utils.Bytes, byte[]>>
                                as(USER_SESSION_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(userOrderStatsSerde)
                );

        sessions.toStream()
                .filter((k, v) -> v != null)
                .map((windowed, value) -> {
                    UserOrderStats stamped = value.withWindow(
                            windowed.window().start(), windowed.window().end());
                    return org.apache.kafka.streams.KeyValue.pair(windowed.key(), stamped);
                })
                .peek((k, v) -> log.debug("Session user={} window [{}..{}] orders={} spent={}",
                        k, v.windowStart(), v.windowEnd(), v.orderCount(), v.totalSpent()))
                .to(props.getTopics().getUserSessions(),
                        Produced.with(Serdes.String(), userOrderStatsSerde));
    }
}
