CREATE TABLE IF NOT EXISTS orders (
    order_id          VARCHAR(64)  PRIMARY KEY,
    saga_id           VARCHAR(64)  NOT NULL UNIQUE,
    customer_id       VARCHAR(64)  NOT NULL,
    product_id        VARCHAR(64)  NOT NULL,
    quantity          INTEGER      NOT NULL,
    unit_price        NUMERIC(19,4) NOT NULL,
    total_amount      NUMERIC(19,4) NOT NULL,
    shipping_address  TEXT         NOT NULL,
    order_status      VARCHAR(32)  NOT NULL,
    saga_status       VARCHAR(32)  NOT NULL,
    failure_reason    VARCHAR(255),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_orders_saga_id  ON orders (saga_id);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status   ON orders (order_status);

-- Inbox/idempotency: skip events we already processed
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    saga_id      VARCHAR(64) NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_processed_events_saga ON processed_events (saga_id);
