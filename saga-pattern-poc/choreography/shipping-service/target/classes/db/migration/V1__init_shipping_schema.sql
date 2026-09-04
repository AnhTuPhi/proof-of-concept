CREATE TABLE IF NOT EXISTS shipments (
    shipment_id    VARCHAR(64) PRIMARY KEY,
    saga_id        VARCHAR(64) NOT NULL UNIQUE,
    order_id       VARCHAR(64) NOT NULL,
    address        TEXT        NOT NULL,
    tracking_number VARCHAR(64),
    estimated_delivery TIMESTAMPTZ,
    status         VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_shipments_saga ON shipments (saga_id);

CREATE TABLE IF NOT EXISTS saga_context (
    saga_id    VARCHAR(64) PRIMARY KEY,
    order_id   VARCHAR(64) NOT NULL,
    address    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    saga_id      VARCHAR(64) NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
