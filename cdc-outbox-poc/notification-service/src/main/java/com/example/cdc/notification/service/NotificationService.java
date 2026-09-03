package com.example.cdc.notification.service;

import com.example.cdc.notification.domain.ProcessedEvent;
import com.example.cdc.notification.dto.OrderEvent;
import com.example.cdc.notification.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotent handler. Uses the event_id (taken from Debezium's "id" header,
 * which is the outbox row UUID) as the dedup key. Inserting it tells us
 * "first time" — a row already present tells us "already processed".
 *
 * We {@code existsById}-check first instead of catching a unique-violation,
 * because Spring's exception translation marks the surrounding transaction
 * rollback-only — which would defeat any later DB writes the handler does.
 *
 * For multi-instance consumers (during rebalance), a race is possible: both
 * see existsById=false and both attempt the insert. The loser's transaction
 * fails with a DataIntegrityViolationException; the Kafka error handler
 * retries, and the second attempt sees existsById=true. Net effect: still
 * exactly-once side-effect dispatch.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ProcessedEventRepository processedRepository;

    public NotificationService(ProcessedEventRepository processedRepository) {
        this.processedRepository = processedRepository;
    }

    @Transactional
    public void handle(UUID eventId, String eventType, OrderEvent event) {
        if (processedRepository.existsById(eventId)) {
            log.info("event already processed, skipping eventId={} type={}", eventId, eventType);
            return;
        }

        String aggregateId = event.id() != null ? event.id().toString() : "<null>";
        processedRepository.save(new ProcessedEvent(eventId, eventType, aggregateId));

        dispatch(eventType, event);
    }

    private void dispatch(String eventType, OrderEvent event) {
        switch (eventType) {
            case "OrderCreated" -> log.info("[notify] customer={} order created for {} x {} (total {})",
                    event.customerId(), event.productSku(), event.quantity(), event.totalAmount());
            case "OrderPaid" -> log.info("[notify] customer={} payment received for order {}",
                    event.customerId(), event.id());
            case "OrderCancelled" -> log.info("[notify] customer={} order {} cancelled",
                    event.customerId(), event.id());
            default -> log.warn("[notify] unknown event type {} for order {}", eventType, event.id());
        }
    }
}
