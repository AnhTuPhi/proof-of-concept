package com.claude.kafka.common.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer defaults that prefer correctness and predictable rebalances.
 * <p>
 * The five settings most teams get wrong:
 * <ol>
 *   <li>{@code enable.auto.commit=false} — auto-commit hides whether the
 *       message was actually processed before the offset moved. Always commit
 *       manually after side effects succeed.</li>
 *   <li>{@code isolation.level=read_committed} — required when the producer
 *       writes inside transactions, otherwise you'd see aborted messages.</li>
 *   <li>{@code partition.assignment.strategy=CooperativeStickyAssignor} —
 *       eager assignors cause "stop-the-world" rebalances. Cooperative gives
 *       you near-zero-pause rolling deploys.</li>
 *   <li>{@code max.poll.interval.ms} — the actual cap on per-message processing
 *       time. If a single record takes longer, you're kicked out of the group
 *       and the partition rebalances mid-batch. Tune this, not session timeout.</li>
 *   <li>{@code group.instance.id} (static membership) — if set, transient
 *       restarts (deploys, crashes) don't trigger rebalance for up to
 *       {@code session.timeout.ms}. Huge win in container envs.</li>
 * </ol>
 */
public final class SafeConsumerProps {
    private SafeConsumerProps() {}

    public static Map<String, Object> base(String bootstrap, String groupId, String clientId) {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Manual commit is non-negotiable for at-least-once processing
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Read only committed messages when producers use transactions
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        // Rebalance-friendly defaults
        p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000);
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 5 * 60_000);

        // Bound batch size so a slow record doesn't kill the whole batch
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        p.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        // Replay from the earliest offset when no committed offset exists
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return p;
    }

    public static Map<String, Object> withStaticMembership(String bootstrap, String groupId,
                                                           String clientId, String instanceId) {
        Map<String, Object> p = base(bootstrap, groupId, clientId);
        p.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, instanceId);
        return p;
    }
}
