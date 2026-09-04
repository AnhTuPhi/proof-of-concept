package com.vndirect.kstreams.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.PaymentEvent;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public EventPublisher(KafkaTemplate<String, String> kafka,
                          AppProperties props,
                          ObjectMapper mapper) {
        this.kafka = kafka;
        this.props = props;
        this.mapper = mapper;
    }

    public CompletableFuture<?> publishOrder(OrderEvent order) {
        return send(props.getTopics().getOrders(), order.orderId(), order);
    }

    public CompletableFuture<?> publishPayment(PaymentEvent payment) {
        return send(props.getTopics().getPayments(), payment.paymentId(), payment);
    }

    public CompletableFuture<?> publishProduct(Product product) {
        return send(props.getTopics().getProducts(), product.productId(), product);
    }

    public CompletableFuture<?> publishUser(User user) {
        return send(props.getTopics().getUsers(), user.userId(), user);
    }

    private CompletableFuture<?> send(String topic, String key, Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            return kafka.send(topic, key, json)
                    .whenComplete((md, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish to {}: {}", topic, ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Serialization failure for {}", topic, e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
