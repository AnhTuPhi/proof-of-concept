package com.example.billing.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Ingests usage events. Idempotency is enforced by the unique constraint on event_id:
 * a replay of the same event_id is silently dropped and the existing row returned.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final UsageEventRepository repo;

    public IngestionService(UsageEventRepository repo) {
        this.repo = repo;
    }

    public IngestionResult ingest(String eventId, String customerId, UsageMetric metric,
                                  BigDecimal quantity, Instant occurredAt) {
        Optional<UsageEvent> existing = repo.findByEventId(eventId);
        if (existing.isPresent()) {
            return new IngestionResult(existing.get(), true);
        }
        try {
            UsageEvent saved = saveNew(eventId, customerId, metric, quantity, occurredAt);
            return new IngestionResult(saved, false);
        } catch (DataIntegrityViolationException race) {
            // Concurrent insert raced us to the unique constraint; treat as a duplicate.
            UsageEvent winner = repo.findByEventId(eventId).orElseThrow(() ->
                    new IllegalStateException("Lost race but row vanished for event " + eventId));
            log.debug("Race on event_id {}; returning existing row", eventId);
            return new IngestionResult(winner, true);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageEvent saveNew(String eventId, String customerId, UsageMetric metric,
                              BigDecimal quantity, Instant occurredAt) {
        UsageEvent e = new UsageEvent(eventId, customerId, metric, quantity, occurredAt);
        return repo.saveAndFlush(e);
    }

    public record IngestionResult(UsageEvent event, boolean duplicate) {
    }
}
