-- Outbox path: products + outbox table written in the same transaction.
CREATE TABLE IF NOT EXISTS sync_outbox.products (
    id              VARCHAR(40) PRIMARY KEY,
    sku             VARCHAR(64) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    price_cents     BIGINT NOT NULL,
    stock           INTEGER NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sync_outbox.outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(40) NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    picked_up_at    TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON sync_outbox.outbox_events (id)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON sync_outbox.outbox_events (aggregate_type, aggregate_id, id);
