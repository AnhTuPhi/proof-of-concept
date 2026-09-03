--
-- Transactional Outbox table. Inserts happen in the same transaction as the
-- business write. Debezium's Postgres connector tails the WAL and routes rows
-- via the Outbox Event Router SMT — see debezium-config/outbox-connector.json.
--
-- REPLICA IDENTITY FULL is required so Debezium can see the row's columns on
-- DELETE (we delete after capture; the SMT needs the BEFORE image).
--
CREATE TABLE outbox_events (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL
);

ALTER TABLE outbox_events REPLICA IDENTITY FULL;

CREATE INDEX idx_outbox_events_created_at ON outbox_events (created_at);
