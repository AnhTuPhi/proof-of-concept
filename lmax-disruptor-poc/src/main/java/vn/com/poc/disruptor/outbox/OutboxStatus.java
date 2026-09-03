package vn.com.poc.disruptor.outbox;

/**
 * PENDING -> IN_FLIGHT -> DISPATCHED (success)
 *                      -> PENDING (retry, with next_attempt_at pushed out)
 *                      -> DEAD_LETTER (attempts exhausted)
 */
public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    DISPATCHED,
    DEAD_LETTER
}
