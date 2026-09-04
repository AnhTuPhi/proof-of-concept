CREATE TABLE IF NOT EXISTS stock (
    product_id  VARCHAR(64) PRIMARY KEY,
    on_hand     INTEGER     NOT NULL,
    reserved    INTEGER     NOT NULL DEFAULT 0,
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reservations (
    reservation_id VARCHAR(64) PRIMARY KEY,
    saga_id        VARCHAR(64) NOT NULL UNIQUE,
    order_id       VARCHAR(64) NOT NULL,
    product_id     VARCHAR(64) NOT NULL,
    quantity       INTEGER     NOT NULL,
    status         VARCHAR(32) NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reservations_saga  ON reservations (saga_id);
CREATE INDEX IF NOT EXISTS idx_reservations_order ON reservations (order_id);

CREATE TABLE IF NOT EXISTS saga_context (
    saga_id    VARCHAR(64) PRIMARY KEY,
    order_id   VARCHAR(64) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    quantity   INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    saga_id      VARCHAR(64) NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
