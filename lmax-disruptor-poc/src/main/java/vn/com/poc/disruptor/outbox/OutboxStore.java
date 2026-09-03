package vn.com.poc.disruptor.outbox;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded, file-backed (H2) relational store for the transactional outbox
 * table. A real deployment would put this table in the same database as the
 * business-logic write it accompanies, in the same local transaction — that
 * is the entire point of the outbox pattern: "update state" and "queue the
 * message to publish" either both commit or both roll back, so you can never
 * end up having changed state without a matching outbound event (or vice
 * versa). This POC keeps the outbox in its own H2 file for simplicity, which
 * is a known simplification — see TECHNICAL.md.
 *
 * <p>{@link #claimBatch(int)} is safe to call concurrently from more than one
 * dispatcher thread/instance: it claims rows with a
 * {@code SELECT ... FOR UPDATE} inside a transaction and flips them to
 * {@code IN_FLIGHT} before committing, so two dispatchers racing for the same
 * row block on the row lock rather than both claiming it.
 */
public final class OutboxStore implements AutoCloseable {

    private final HikariDataSource dataSource;

    public OutboxStore(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        // DB_CLOSE_DELAY=-1 keeps the embedded database open for the life of the
        // JVM even if the connection pool's idle count briefly drops to zero.
        // Without it, a file-mode H2 database physically closes (and reopens on
        // the next connection) whenever the last connection closes — under a
        // pool cycling connections quickly that repeated close/reopen raced with
        // in-flight MVStore reads and corrupted the file in testing.
        config.setJdbcUrl(jdbcUrl.contains("DB_CLOSE_DELAY") ? jdbcUrl : jdbcUrl + ";DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(8);
        config.setPoolName("outbox-pool");
        this.dataSource = new HikariDataSource(config);
        initSchema();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    private void initSchema() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      exchange_seq BIGINT NOT NULL,
                      session_id INT NOT NULL,
                      symbol VARCHAR(16) NOT NULL,
                      payload VARCHAR(2000) NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      attempts INT NOT NULL DEFAULT 0,
                      next_attempt_at TIMESTAMP NOT NULL,
                      created_at TIMESTAMP NOT NULL,
                      dispatched_at TIMESTAMP NULL,
                      last_error VARCHAR(500) NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(status, next_attempt_at)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialise outbox schema", e);
        }
    }

    /**
     * Claims up to {@code batchSize} due rows (status=PENDING, next_attempt_at
     * in the past) and flips them to IN_FLIGHT in one transaction.
     *
     * <p>Opens (and closes) its own connection — convenient for tests and
     * one-off callers. {@link OutboxDispatcher} uses the connection-scoped
     * overload below instead, to avoid a connection-pool checkout per call at
     * high row volume; see that overload's note.
     */
    public List<OutboxRecord> claimBatch(int batchSize) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return claimBatch(c, batchSize);
        }
    }

    /**
     * Same as {@link #claimBatch(int)}, but reuses a caller-supplied
     * connection instead of checking one out of the pool. A dispatcher
     * worker thread holds one {@link Connection} for its entire poll loop —
     * claiming 500 rows a million times over would otherwise mean a million
     * pool checkouts just for this one call, on top of the ones for every
     * per-row status update below. That connection churn, not the FOR UPDATE
     * locking itself, was the dominant cost observed at multi-million-row
     * scale during benchmarking.
     */
    public List<OutboxRecord> claimBatch(Connection c, int batchSize) throws SQLException {
        List<OutboxRecord> claimed = new ArrayList<>(batchSize);
        boolean previousAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            // Deliberately no ORDER BY id: with millions of rows, an id-ordered
            // scan has to walk past every already-claimed (non-PENDING) row that
            // sorts before the remaining PENDING ones, turning each claim call
            // into progressively more work as the table drains — effectively
            // O(n^2) over a full run. Dropping the ORDER BY lets H2 use the
            // (status, next_attempt_at) index directly to jump straight to due
            // rows. Dispatch order across different outbox rows was never a
            // correctness requirement (each row's own retry/backoff is
            // independent), so nothing is lost by not sorting.
            try (PreparedStatement select = c.prepareStatement("""
                    SELECT id, exchange_seq, session_id, symbol, payload, attempts
                    FROM outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
                    LIMIT ?
                    FOR UPDATE
                    """)) {
                select.setInt(1, batchSize);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        claimed.add(new OutboxRecord(
                                rs.getLong("id"), rs.getLong("exchange_seq"), rs.getInt("session_id"),
                                rs.getString("symbol"), rs.getString("payload"), rs.getInt("attempts")));
                    }
                }
            }
            if (!claimed.isEmpty()) {
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE outbox SET status = 'IN_FLIGHT' WHERE id = ?")) {
                    for (OutboxRecord r : claimed) {
                        update.setLong(1, r.id());
                        update.addBatch();
                    }
                    update.executeBatch();
                }
            }
            c.commit();
        } finally {
            c.setAutoCommit(previousAutoCommit);
        }
        return claimed;
    }

    public void markDispatched(long id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            markDispatched(c, id);
        }
    }

    public void markDispatched(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET status = 'DISPATCHED', dispatched_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void markRetry(long id, int attempts, Instant nextAttemptAt, String error) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            markRetry(c, id, attempts, nextAttemptAt, error);
        }
    }

    public void markRetry(Connection c, long id, int attempts, Instant nextAttemptAt, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE outbox
                SET status = 'PENDING', attempts = ?, next_attempt_at = ?, last_error = ?
                WHERE id = ?
                """)) {
            ps.setInt(1, attempts);
            ps.setTimestamp(2, Timestamp.from(nextAttemptAt));
            ps.setString(3, truncate(error));
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void markDeadLetter(long id, int attempts, String error) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            markDeadLetter(c, id, attempts, error);
        }
    }

    public void markDeadLetter(Connection c, long id, int attempts, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE outbox
                SET status = 'DEAD_LETTER', attempts = ?, last_error = ?
                WHERE id = ?
                """)) {
            ps.setInt(1, attempts);
            ps.setString(2, truncate(error));
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public long countByStatus(String status) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM outbox WHERE status = ?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
