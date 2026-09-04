CREATE TABLE IF NOT EXISTS payments (
    payment_id     VARCHAR(64) PRIMARY KEY,
    saga_id        VARCHAR(64) NOT NULL UNIQUE,
    order_id       VARCHAR(64) NOT NULL,
    customer_id    VARCHAR(64) NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    status         VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payments_saga  ON payments (saga_id);
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments (order_id);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    saga_id      VARCHAR(64) NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
