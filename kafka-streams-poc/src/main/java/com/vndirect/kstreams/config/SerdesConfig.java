package com.vndirect.kstreams.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vndirect.kstreams.model.CategoryRevenue;
import com.vndirect.kstreams.model.CompletedOrder;
import com.vndirect.kstreams.model.EnrichedOrder;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.PaymentEvent;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import com.vndirect.kstreams.model.UserOrderStats;
import com.vndirect.kstreams.serdes.JsonSerde;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SerdesConfig {

    @Bean
    public Serde<OrderEvent> orderEventSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, OrderEvent.class);
    }

    @Bean
    public Serde<PaymentEvent> paymentEventSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, PaymentEvent.class);
    }

    @Bean
    public Serde<Product> productSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, Product.class);
    }

    @Bean
    public Serde<User> userSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, User.class);
    }

    @Bean
    public Serde<EnrichedOrder> enrichedOrderSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, EnrichedOrder.class);
    }

    @Bean
    public Serde<CompletedOrder> completedOrderSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, CompletedOrder.class);
    }

    @Bean
    public Serde<CategoryRevenue> categoryRevenueSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, CategoryRevenue.class);
    }

    @Bean
    public Serde<UserOrderStats> userOrderStatsSerde(ObjectMapper mapper) {
        return new JsonSerde<>(mapper, UserOrderStats.class);
    }
}
