package vn.com.poc.disruptor.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.poc.disruptor.metrics.PipelineMetrics;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDispatcherTest {

    private OutboxStore openStore(Path dir) {
        String jdbcUrl = "jdbc:h2:" + dir.resolve("outbox").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        return new OutboxStore(jdbcUrl);
    }

    private void insertPendingRow(OutboxStore store, long exchangeSeq) throws SQLException {
        try (Connection c = store.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO outbox(exchange_seq, session_id, symbol, payload, status, attempts, next_attempt_at, created_at)
                     VALUES (?, 0, 'VND', '{}', 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                     """)) {
            ps.setLong(1, exchangeSeq);
            ps.executeUpdate();
        }
    }

    @Test
    void succeedsOnFirstAttemptWhenDownstreamIsHealthy(@TempDir Path dir) throws Exception {
        try (OutboxStore store = openStore(dir)) {
            insertPendingRow(store, 1);
            insertPendingRow(store, 2);

            PipelineMetrics metrics = new PipelineMetrics();
            DownstreamPublisher alwaysSucceeds = record -> { };
            OutboxDispatcher dispatcher = new OutboxDispatcher(store, alwaysSucceeds,
                    RetryBackoffPolicy.defaultPolicy(), metrics, 1, 10, Duration.ofMillis(10));
            dispatcher.start();
            try {
                assertTrue(dispatcher.awaitDrain(Duration.ofSeconds(5)));
            } finally {
                dispatcher.close();
            }

            assertEquals(2, store.countByStatus("DISPATCHED"));
            assertEquals(0, store.countByStatus("DEAD_LETTER"));
            assertEquals(2, metrics.snapshot().outboxDispatched());
        }
    }

    @Test
    void retriesThenSucceedsAfterTransientFailures(@TempDir Path dir) throws Exception {
        try (OutboxStore store = openStore(dir)) {
            insertPendingRow(store, 1);

            AtomicInteger attempts = new AtomicInteger();
            DownstreamPublisher failsTwiceThenSucceeds = record -> {
                if (attempts.incrementAndGet() <= 2) {
                    throw new DownstreamPublisher.PublishException("boom attempt " + attempts.get());
                }
            };

            PipelineMetrics metrics = new PipelineMetrics();
            // tiny base delay so the test doesn't wait on real backoff durations
            RetryBackoffPolicy fastBackoff = new RetryBackoffPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5);
            OutboxDispatcher dispatcher = new OutboxDispatcher(store, failsTwiceThenSucceeds,
                    fastBackoff, metrics, 1, 10, Duration.ofMillis(10));
            dispatcher.start();
            try {
                assertTrue(dispatcher.awaitDrain(Duration.ofSeconds(10)));
            } finally {
                dispatcher.close();
            }

            assertEquals(1, store.countByStatus("DISPATCHED"));
            assertTrue(attempts.get() >= 3, "expected at least 2 failures then a success, got " + attempts.get());
            assertTrue(metrics.snapshot().outboxRetries() >= 2);
        }
    }

    @Test
    void movesToDeadLetterAfterExhaustingRetries(@TempDir Path dir) throws Exception {
        try (OutboxStore store = openStore(dir)) {
            insertPendingRow(store, 1);

            DownstreamPublisher alwaysFails = record -> {
                throw new DownstreamPublisher.PublishException("permanently down");
            };

            PipelineMetrics metrics = new PipelineMetrics();
            RetryBackoffPolicy fastBackoff = new RetryBackoffPolicy(Duration.ofMillis(1), Duration.ofMillis(5), 3);
            OutboxDispatcher dispatcher = new OutboxDispatcher(store, alwaysFails,
                    fastBackoff, metrics, 1, 10, Duration.ofMillis(5));
            dispatcher.start();
            try {
                assertTrue(dispatcher.awaitDrain(Duration.ofSeconds(10)));
            } finally {
                dispatcher.close();
            }

            assertEquals(0, store.countByStatus("DISPATCHED"));
            assertEquals(1, store.countByStatus("DEAD_LETTER"));
            assertEquals(1, metrics.snapshot().outboxDeadLettered());
        }
    }

    @Test
    void concurrentDispatchersNeverDoubleDispatchTheSameRow(@TempDir Path dir) throws Exception {
        try (OutboxStore store = openStore(dir)) {
            int rowCount = 200;
            for (long i = 0; i < rowCount; i++) {
                insertPendingRow(store, i);
            }

            AtomicInteger publishCalls = new AtomicInteger();
            DownstreamPublisher countingPublisher = record -> publishCalls.incrementAndGet();

            PipelineMetrics metrics = new PipelineMetrics();
            OutboxDispatcher dispatcher = new OutboxDispatcher(store, countingPublisher,
                    RetryBackoffPolicy.defaultPolicy(), metrics, 4, 10, Duration.ofMillis(10));
            dispatcher.start();
            try {
                assertTrue(dispatcher.awaitDrain(Duration.ofSeconds(10)));
            } finally {
                dispatcher.close();
            }

            assertEquals(rowCount, publishCalls.get(), "every row must be published exactly once, never twice");
            assertEquals(rowCount, store.countByStatus("DISPATCHED"));
        }
    }
}
