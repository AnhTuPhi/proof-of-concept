package com.vndirect.kstreams.api;

import com.vndirect.kstreams.model.CategoryRevenue;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import com.vndirect.kstreams.model.UserOrderStats;
import com.vndirect.kstreams.topology.OrderEnrichmentTopology;
import com.vndirect.kstreams.topology.WindowedAggregationsTopology;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive Queries: read directly from the locally-materialized state
 * stores without a separate database. Handy for dashboards or "current
 * value" lookups without re-deriving from the changelog topic.
 */
@RestController
@RequestMapping("/api/state")
public class StateStoreController {

    private final StreamsBuilderFactoryBean factoryBean;

    public StateStoreController(StreamsBuilderFactoryBean factoryBean) {
        this.factoryBean = factoryBean;
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<Product> getProduct(@PathVariable String productId) {
        return readKv(OrderEnrichmentTopology.PRODUCTS_STORE, Product.class, productId);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<User> getUser(@PathVariable String userId) {
        return readKv(OrderEnrichmentTopology.USERS_STORE, User.class, userId);
    }

    @GetMapping("/category-revenue")
    public ResponseEntity<List<CategoryRevenue>> recentCategoryRevenue(
            @RequestParam(defaultValue = "10") int windowMinutes) {
        KafkaStreams streams = streamsOrThrow();
        ReadOnlyWindowStore<String, CategoryRevenue> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                        WindowedAggregationsTopology.CATEGORY_REVENUE_STORE,
                        QueryableStoreTypes.windowStore()));

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(windowMinutes));
        List<CategoryRevenue> results = new ArrayList<>();
        try (KeyValueIterator<org.apache.kafka.streams.kstream.Windowed<String>, CategoryRevenue> it =
                     store.fetchAll(from, to)) {
            while (it.hasNext()) {
                var kv = it.next();
                results.add(kv.value.withWindow(
                        kv.key.window().start(), kv.key.window().end()));
            }
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/user-stats/{userId}")
    public ResponseEntity<List<UserOrderStats>> userStats(
            @PathVariable String userId,
            @RequestParam(defaultValue = "30") int windowMinutes) {
        KafkaStreams streams = streamsOrThrow();
        ReadOnlyWindowStore<String, UserOrderStats> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                        WindowedAggregationsTopology.USER_ORDER_COUNT_STORE,
                        QueryableStoreTypes.windowStore()));

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(windowMinutes));
        List<UserOrderStats> results = new ArrayList<>();
        try (WindowStoreIterator<UserOrderStats> it = store.fetch(userId, from, to)) {
            while (it.hasNext()) {
                var kv = it.next();
                results.add(kv.value.withWindow(kv.key, kv.key));
            }
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        KafkaStreams streams = streamsOrThrow();
        Map<String, Object> info = new HashMap<>();
        info.put("state", streams.state().name());
        info.put("threads", streams.metadataForLocalThreads().size());
        return ResponseEntity.ok(info);
    }

    private <V> ResponseEntity<V> readKv(String storeName, Class<V> type, String key) {
        KafkaStreams streams = streamsOrThrow();
        ReadOnlyKeyValueStore<String, V> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                        storeName, QueryableStoreTypes.keyValueStore()));
        V value = store.get(key);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    private KafkaStreams streamsOrThrow() {
        KafkaStreams s = factoryBean.getKafkaStreams();
        if (s == null) {
            throw new IllegalStateException("KafkaStreams not running yet");
        }
        if (s.state() != KafkaStreams.State.RUNNING) {
            throw new IllegalStateException("KafkaStreams not RUNNING (state=" + s.state() + ")");
        }
        return s;
    }
}
