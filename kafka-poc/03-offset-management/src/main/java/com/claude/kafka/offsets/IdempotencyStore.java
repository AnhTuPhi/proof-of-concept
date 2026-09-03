package com.claude.kafka.offsets;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persistent dedup table backing the {@link CommitMode#IDEMPOTENT_AFTER} mode.
 * <p>
 * Insert-or-reject pattern: the unique constraint on {@code message_id}
 * is the actual idempotency guard. If the insert succeeds, the consumer
 * proceeds with the side effect; if it fails with {@link DuplicateKeyException},
 * the message is treated as already-processed and committed.
 * <p>
 * Important: in production, the insert and the side effect must be in the
 * SAME database transaction. Otherwise you can insert the dedup row, crash,
 * and never replay the side effect.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private final JdbcTemplate jdbc;

    /**
     * @return {@code true} if this is the first time we've seen {@code messageId},
     *         {@code false} if it's a replay.
     */
    public boolean markProcessed(String messageId, String topic, String consumerId) {
        try {
            jdbc.update("INSERT INTO appuser.processed_messages " +
                            "(message_id, topic, consumer_id) VALUES (?, ?, ?)",
                    messageId, topic, consumerId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
