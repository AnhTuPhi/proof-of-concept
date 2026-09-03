package com.claude.kafka.offsets;

/**
 * The four flavors of offset commit, with their failure modes:
 * <ul>
 *   <li>{@link #AUTO} — broker auto-commits every {@code auto.commit.interval.ms}.
 *       <strong>Loses messages</strong> if processing fails after the offset
 *       is committed but before the side effect completes.</li>
 *   <li>{@link #SYNC_BEFORE} — manual commit before processing.
 *       Same loss mode as auto: a crash mid-processing leaves the offset
 *       committed but no work done.</li>
 *   <li>{@link #SYNC_AFTER} — manual commit after processing.
 *       <strong>At-least-once</strong>: a crash between processing and
 *       commit causes re-delivery on restart. Needs an idempotent handler.</li>
 *   <li>{@link #IDEMPOTENT_AFTER} — same as SYNC_AFTER but the handler writes
 *       {@code message_id} to a DB unique index, skipping duplicates.
 *       Closest you get to exactly-once side effects.</li>
 * </ul>
 */
public enum CommitMode {
    AUTO,
    SYNC_BEFORE,
    SYNC_AFTER,
    IDEMPOTENT_AFTER
}
