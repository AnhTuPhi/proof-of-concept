package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.EventHandler;
import vn.com.poc.disruptor.bench.LatencyRecorder;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.metrics.PipelineMetrics;
import vn.com.poc.disruptor.outbox.OutboxStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Last stage of the Disruptor chain: for every event that actually changed
 * business state (same skip predicate as {@link BusinessLogicHandler} —
 * skip poisoned/duplicate), queue exactly one outbox row.
 *
 * <p>Batches inserts with JDBC's {@code addBatch()}/{@code executeBatch()}
 * and commits once per Disruptor batch ({@code endOfBatch}), the same
 * batching shape as {@link JournalHandler}, for the same reason: the cost of
 * a network/disk round trip is amortized over however many events the
 * Disruptor coalesced into this batch.
 *
 * <p>Single instance, single writer thread — the JDBC {@link Connection}
 * here is never touched by any other thread, so it needs no pooling or
 * synchronization of its own (it borrows one connection from the shared
 * {@link OutboxStore} pool for the lifetime of the pipeline).
 */
public final class OutboxHandler implements EventHandler<MarketEvent>, AutoCloseable {

    private static final String INSERT_SQL = """
            INSERT INTO outbox(exchange_seq, session_id, symbol, payload, status, attempts, next_attempt_at, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    private final PipelineMetrics metrics;
    private final LatencyRecorder latencyRecorder; // nullable — only wired up in benchmark mode
    private final Connection connection;
    private final PreparedStatement insertStatement;
    private int pendingInBatch = 0;

    public OutboxHandler(OutboxStore store, PipelineMetrics metrics) throws SQLException {
        this(store, metrics, null);
    }

    public OutboxHandler(OutboxStore store, PipelineMetrics metrics, LatencyRecorder latencyRecorder) throws SQLException {
        this.metrics = metrics;
        this.latencyRecorder = latencyRecorder;
        DataSource ds = store.dataSource();
        this.connection = ds.getConnection();
        this.connection.setAutoCommit(false);
        this.insertStatement = connection.prepareStatement(INSERT_SQL);
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) throws SQLException {
        if (latencyRecorder != null) {
            latencyRecorder.recordNanos(System.nanoTime() - event.ingestNanos());
        }
        if (!event.isPoisoned() && !event.isDuplicate()) {
            insertStatement.setLong(1, event.exchangeSeq());
            insertStatement.setInt(2, event.sessionId());
            insertStatement.setString(3, event.symbol());
            insertStatement.setString(4, event.toJsonPayload());
            insertStatement.addBatch();
            pendingInBatch++;
            metrics.incOutboxCreated();
        }

        if (endOfBatch && pendingInBatch > 0) {
            insertStatement.executeBatch();
            connection.commit();
            pendingInBatch = 0;
        }
    }

    @Override
    public void close() throws SQLException {
        if (pendingInBatch > 0) {
            insertStatement.executeBatch();
            connection.commit();
        }
        insertStatement.close();
        connection.close();
    }
}
