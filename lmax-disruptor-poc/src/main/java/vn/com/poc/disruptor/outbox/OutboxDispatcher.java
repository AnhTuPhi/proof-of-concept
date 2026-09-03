package vn.com.poc.disruptor.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.com.poc.disruptor.metrics.PipelineMetrics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the outbox table for due rows and attempts to hand them to a
 * {@link DownstreamPublisher}, off the Disruptor's own threads entirely —
 * publishing to a flaky downstream must never stall the ring buffer.
 *
 * <p>On failure: increment {@code attempts}, and if the retry budget
 * ({@link RetryBackoffPolicy#maxAttempts()}) isn't exhausted, push
 * {@code next_attempt_at} out by a full-jitter exponential delay and leave
 * the row PENDING for a later poll to pick up again. Once exhausted, the row
 * moves to DEAD_LETTER — a deliberately terminal, visible state (a real
 * deployment would alert on it) rather than a message that silently
 * disappears after N failed attempts.
 *
 * <p>Runs {@code workerCount} independent poller threads; safe to do because
 * {@link OutboxStore#claimBatch(int)} claims rows under a row lock
 * ({@code SELECT ... FOR UPDATE}), so two pollers racing for the same row
 * simply serialize on that lock instead of double-publishing it.
 */
public final class OutboxDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final DownstreamPublisher publisher;
    private final RetryBackoffPolicy backoff;
    private final PipelineMetrics metrics;
    private final int batchSize;
    private final Duration idlePoll;
    private final Thread[] workers;
    private final Object claimLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public OutboxDispatcher(OutboxStore store, DownstreamPublisher publisher, RetryBackoffPolicy backoff,
                             PipelineMetrics metrics, int workerCount, int batchSize, Duration idlePoll) {
        this.store = store;
        this.publisher = publisher;
        this.backoff = backoff;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.idlePoll = idlePoll;
        this.workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workers[i] = new Thread(this::pollLoop, "outbox-dispatcher-" + i);
            workers[i].setDaemon(true);
        }
    }

    public void start() {
        for (Thread w : workers) {
            w.start();
        }
    }

    private void pollLoop() {
        // One connection held for this thread's entire lifetime, instead of a
        // pool checkout per claim/update call. At small scale (tests) the
        // difference is invisible; at the multi-million-row scale this
        // benchmark runs at, a checkout-per-row turned an I/O-bound stage into
        // a connection-pool-bound one and dominated the measured time.
        Connection conn;
        try {
            conn = store.dataSource().getConnection();
        } catch (SQLException e) {
            log.error("outbox dispatcher thread could not obtain a connection, exiting", e);
            return;
        }
        try {
            while (running.get()) {
                try {
                    // Serialize the claim step across this JVM's own worker threads.
                    // The DB-level SELECT...FOR UPDATE in claimBatch is what makes
                    // claiming safe across *separate processes/instances* — the real
                    // horizontal-scaling case. Inside one JVM, letting several
                    // threads all race on that same FOR UPDATE query against the
                    // embedded demo database at high row volume was observed (under
                    // test, at ~100k+ rows with heavy retry churn) to escalate into
                    // lock-wait timeouts and, occasionally, MVStore-level corruption
                    // — an embedded-H2/single-file limitation, not a flaw in the
                    // claim-then-publish design itself. An in-process lock avoids
                    // stressing that path for something a JVM-local mutex already
                    // solves for free.
                    List<OutboxRecord> batch;
                    synchronized (claimLock) {
                        batch = store.claimBatch(conn, batchSize);
                    }
                    if (batch.isEmpty()) {
                        Thread.sleep(idlePoll.toMillis());
                        continue;
                    }
                    for (OutboxRecord record : batch) {
                        dispatchOne(conn, record);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (SQLException e) {
                    log.error("outbox dispatcher SQL error, reconnecting after backoff", e);
                    conn = reconnect(conn);
                }
            }
        } finally {
            closeQuietly(conn);
        }
    }

    private Connection reconnect(Connection stale) {
        closeQuietly(stale);
        sleepQuietly(idlePoll);
        try {
            return store.dataSource().getConnection();
        } catch (SQLException e) {
            log.error("failed to reconnect outbox dispatcher connection", e);
            return stale; // caller will keep trying and failing loudly rather than NPE
        }
    }

    private void dispatchOne(Connection conn, OutboxRecord record) {
        try {
            publisher.publish(record);
            store.markDispatched(conn, record.id());
            metrics.incOutboxDispatched();
        } catch (DownstreamPublisher.PublishException e) {
            metrics.incOutboxRetries();
            int attempts = record.attempts() + 1;
            try {
                if (backoff.isExhausted(attempts)) {
                    store.markDeadLetter(conn, record.id(), attempts, e.getMessage());
                    metrics.incOutboxDeadLettered();
                    log.warn("outbox id={} moved to DEAD_LETTER after {} attempts: {}",
                            record.id(), attempts, e.getMessage());
                } else {
                    Instant nextAttemptAt = Instant.now().plus(backoff.nextDelay(attempts));
                    store.markRetry(conn, record.id(), attempts, nextAttemptAt, e.getMessage());
                }
            } catch (SQLException sqlEx) {
                log.error("failed to record retry/dead-letter state for outbox id={}", record.id(), sqlEx);
            }
        } catch (SQLException e) {
            log.error("failed to mark outbox id={} dispatched", record.id(), e);
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // returning/closing a connection failing is not actionable here
            }
        }
    }

    /** Test/benchmark helper: block until every outbox row is DISPATCHED or DEAD_LETTER, or the timeout elapses. */
    public boolean awaitDrain(Duration timeout) throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            long inFlight = store.countByStatus("PENDING") + store.countByStatus("IN_FLIGHT");
            if (inFlight == 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        for (Thread w : workers) {
            w.interrupt();
        }
        for (Thread w : workers) {
            try {
                w.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
