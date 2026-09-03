package com.claude.kafka.avro;

import com.claude.kafka.avro.model.Address;
import com.claude.kafka.avro.model.OrderEvent;
import com.claude.kafka.avro.model.OrderStatus;
import com.claude.kafka.common.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Avro producer/consumer round-trip with the registry.
 * <pre>
 *   POST /avro/v1?withAddress=false   - send v1-style record
 *   POST /avro/v2?withAddress=true    - send with the nullable shipping address
 * </pre>
 * Watch the consumer logs: both v1 and v2 are read correctly because the
 * registry-enforced BACKWARD compat keeps them readable from one schema.
 */
@Slf4j
@RestController
@RequestMapping("/avro")
@RequiredArgsConstructor
public class AvroDemoController {

    private final KafkaTemplate<String, Object> kafka;

    @PostMapping("/v2")
    public Map<String, Object> sendV2(@RequestParam(defaultValue = "true") boolean withAddress) {
        String orderId = UUID.randomUUID().toString();

        ByteBuffer total = ByteBuffer.wrap(
                new BigDecimal("99.99").setScale(2).unscaledValue().toByteArray());

        OrderEvent e = OrderEvent.newBuilder()
                .setOrderId(orderId)
                .setCustomerId("cust-1")
                .setStatus(OrderStatus.PLACED)
                .setTotalAmount(total)
                .setCurrency("USD")
                .setOccurredAt(Instant.now().toEpochMilli())
                .setShippingAddress(withAddress
                        ? Address.newBuilder().setLine1("1 Park Ave").setCity("NYC").setCountry("US").build()
                        : null)
                .build();

        kafka.send(Topics.ORDERS_PLACED + ".avro", orderId, e);
        return Map.of("orderId", orderId, "schemaIncludesAddress", withAddress);
    }

    @KafkaListener(topics = Topics.ORDERS_PLACED + ".avro",
            containerFactory = "avroListenerFactory",
            groupId = "avro-demo-consumer")
    public void onEvent(OrderEvent e) {
        log.info("Got Avro event: orderId={} status={} addressPresent={}",
                e.getOrderId(), e.getStatus(), e.getShippingAddress() != null);
    }
}
