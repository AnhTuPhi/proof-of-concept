CREATE TABLE IF NOT EXISTS transactions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS source_transactions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id  VARCHAR(64)  NOT NULL UNIQUE,
    account_id      VARCHAR(64)  NOT NULL,
    symbol          VARCHAR(32)  NOT NULL,
    side            VARCHAR(8)   NOT NULL,
    quantity        NUMERIC(20,4) NOT NULL,
    price           NUMERIC(20,4) NOT NULL,
    trade_ts        TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS ingest_errors (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name     VARCHAR(64)  NOT NULL,
    step_name    VARCHAR(64)  NOT NULL,
    partition_id VARCHAR(64),
    payload      CLOB,
    error_class  VARCHAR(256),
    error_msg    CLOB,
    occurred_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
