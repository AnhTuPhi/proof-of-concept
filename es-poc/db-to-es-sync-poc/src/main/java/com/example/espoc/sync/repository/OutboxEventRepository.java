package com.example.espoc.sync.repository;

import com.example.espoc.sync.model.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Atomic pickup via SKIP LOCKED — safe under multiple poller instances on the same DB.
     * Holds a row-level lock until commit; SKIP LOCKED prevents pollers from blocking each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({ @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0") })
    @Query(value = """
            SELECT * FROM sync_outbox.outbox_events
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> pickPending(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();

    @Query("SELECT MIN(o.createdAt) FROM OutboxEvent o WHERE o.publishedAt IS NULL")
    Instant oldestPendingCreatedAt();
}
