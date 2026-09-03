package com.example.cdc.order.service;

import com.example.cdc.order.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Once Debezium has captured an outbox row, it lives in the WAL — the row
 * itself is no longer needed. This job deletes rows older than the retention
 * window so the outbox table stays small.
 *
 * Important: the retention window must be LONGER than the worst-case Debezium
 * lag, otherwise rows could be deleted before being captured. The default
 * (7 days) is generous; tune for your operational tolerance.
 */
@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository repository;
    private final Duration retention;

    public OutboxCleanupJob(OutboxEventRepository repository,
                            @Value("${app.outbox.retention:P7D}") Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    @Scheduled(cron = "${app.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldEvents() {
        Instant threshold = Instant.now().minus(retention);
        int deleted = repository.deleteOlderThan(threshold);
        if (deleted > 0) {
            log.info("outbox cleanup: deleted {} events older than {}", deleted, threshold);
        } else {
            log.debug("outbox cleanup: no events older than {}", threshold);
        }
    }
}
