-- Target table where ingested rows land.
CREATE TABLE IF NOT EXISTS transactions (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  VARCHAR(64)  NOT NULL UNIQUE,
    account_id      VARCHAR(64)  NOT NULL,
    symbol          VARCHAR(32)  NOT NULL,
    side            VARCHAR(8)   NOT NULL,
    quantity        NUMERIC(20,4) NOT NULL,
    price           NUMERIC(20,4) NOT NULL,
    trade_ts        TIMESTAMP    NOT NULL,
    ingested_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source          VARCHAR(16)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_transactions_account_ts ON transactions(account_id, trade_ts);
CREATE INDEX IF NOT EXISTS idx_transactions_source ON transactions(source);

-- Source table used by the DB-to-DB ingest job.
CREATE TABLE IF NOT EXISTS source_transactions (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  VARCHAR(64)  NOT NULL UNIQUE,
    account_id      VARCHAR(64)  NOT NULL,
    symbol          VARCHAR(32)  NOT NULL,
    side            VARCHAR(8)   NOT NULL,
    quantity        NUMERIC(20,4) NOT NULL,
    price           NUMERIC(20,4) NOT NULL,
    trade_ts        TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_source_transactions_id ON source_transactions(id);

-- Audit table for rejected rows (populated by skip listeners).
CREATE TABLE IF NOT EXISTS ingest_errors (
    id           BIGSERIAL PRIMARY KEY,
    job_name     VARCHAR(64)  NOT NULL,
    step_name    VARCHAR(64)  NOT NULL,
    partition_id VARCHAR(64),
    payload      TEXT,
    error_class  VARCHAR(256),
    error_msg    TEXT,
    occurred_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
