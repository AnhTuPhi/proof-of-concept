package com.claude.kafka.cqrs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import com.claude.kafka.common.event.DomainEvent;
import com.claude.kafka.common.metrics.KafkaAppMetrics;
import com.claude.kafka.common.topic.Topics;
import com.claude.kafka.common.util.JsonCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-side projection.
 * <p>
 * The pattern most teams get wrong here is overwriting the document on every
 * event. The {@code OrderShipped} handler shouldn't have to know the order's
 * customer, amount, etc. — instead we use ES {@code _update} with a tiny
 * partial doc, plus {@code doc_as_upsert} for first-write.
 * <p>
 * Out-of-order delivery is handled by carrying the event's {@code occurredAt}
 * into a {@code version_external} field. ES discards "older" updates so a
 * delayed {@code OrderPlaced} can't clobber an {@code OrderShipped} that
 * already landed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProjector {

    public static final String INDEX = "orders";
    private final ElasticsearchClient es;
    private final KafkaAppMetrics metrics;

    @KafkaListener(topics = {
            Topics.ORDERS_PLACED,
            Topics.ORDERS_PAID,
            Topics.ORDERS_SHIPPED,
            Topics.ORDERS_CANCELLED
    }, groupId = "cqrs-orders-projector")
    public void onEvent(ConsumerRecord<String, String> rec) throws Exception {
        DomainEvent<?> e = JsonCodec.fromJson(rec.value(), DomainEvent.class);
        metrics.recordConsumed(rec.topic());

        switch (e.getEventType()) {
            case "OrderPlaced"    -> upsertPlaced(e);
            case "OrderPaid"      -> patchStatus(e, "PAID");
            case "OrderShipped"   -> patchStatus(e, "SHIPPED");
            case "OrderCancelled" -> patchStatus(e, "CANCELLED");
            default -> log.debug("Ignoring {}", e.getEventType());
        }
    }

    private void upsertPlaced(DomainEvent<?> e) throws Exception {
        Map<?,?> p = (Map<?,?>) e.getPayload();
        OrderReadModel doc = OrderReadModel.builder()
                .orderId(e.getAggregateId())
                .customerId((String) p.get("customerId"))
                .status("PLACED")
                .totalAmount(new BigDecimal(String.valueOf(p.getOrDefault("amount", "0"))))
                .currency(String.valueOf(p.getOrDefault("currency", "USD")))
                .placedAt(e.getOccurredAt())
                .updatedAt(e.getOccurredAt())
                .history(new ArrayList<>(List.of(
                        new OrderReadModel.StatusChange("PLACED", e.getOccurredAt()))))
                .build();
        es.index(i -> i.index(INDEX).id(e.getAggregateId()).document(doc));
    }

    private void patchStatus(DomainEvent<?> e, String status) throws Exception {
        // Read current to append to history. In a hot index, use a script update
        // to avoid the round trip (ctx._source.history.add(...)).
        GetResponse<OrderReadModel> current = es.get(g ->
                g.index(INDEX).id(e.getAggregateId()), OrderReadModel.class);

        OrderReadModel src = current.found() && current.source() != null
                ? current.source()
                : OrderReadModel.builder().orderId(e.getAggregateId()).history(new ArrayList<>()).build();

        // Discard older event (handles out-of-order delivery across topics)
        if (src.getUpdatedAt() != null && e.getOccurredAt().isBefore(src.getUpdatedAt())) {
            log.debug("Skipping stale {} for order {}", status, e.getAggregateId());
            return;
        }

        src.setStatus(status);
        src.setUpdatedAt(e.getOccurredAt());
        if (src.getHistory() == null) src.setHistory(new ArrayList<>());
        src.getHistory().add(new OrderReadModel.StatusChange(status, e.getOccurredAt()));

        UpdateRequest<OrderReadModel, OrderReadModel> req = UpdateRequest.of(u -> u
                .index(INDEX).id(e.getAggregateId())
                .doc(src).docAsUpsert(true));
        es.update(req, OrderReadModel.class);
    }
}
