CREATE TABLE IF NOT EXISTS orders (
    order_id          VARCHAR(64) PRIMARY KEY,
    workflow_id       VARCHAR(128) NOT NULL UNIQUE,
    customer_id       VARCHAR(64) NOT NULL,
    product_id        VARCHAR(64) NOT NULL,
    quantity          INTEGER     NOT NULL,
    unit_price        NUMERIC(19,4) NOT NULL,
    total_amount      NUMERIC(19,4) NOT NULL,
    shipping_address  TEXT        NOT NULL,
    status            VARCHAR(32) NOT NULL,
    failure_reason    VARCHAR(255),
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_orders_workflow ON orders (workflow_id);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id);

CREATE TABLE IF NOT EXISTS payments (
    payment_id   VARCHAR(64) PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL,
    customer_id  VARCHAR(64) NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payments_order ON payments (order_id);

CREATE TABLE IF NOT EXISTS stock (
    product_id VARCHAR(64) PRIMARY KEY,
    on_hand    INTEGER     NOT NULL,
    reserved   INTEGER     NOT NULL DEFAULT 0,
    version    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reservations (
    reservation_id VARCHAR(64) PRIMARY KEY,
    order_id       VARCHAR(64) NOT NULL,
    product_id     VARCHAR(64) NOT NULL,
    quantity       INTEGER     NOT NULL,
    status         VARCHAR(32) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reservations_order ON reservations (order_id);

CREATE TABLE IF NOT EXISTS shipments (
    shipment_id   VARCHAR(64) PRIMARY KEY,
    order_id      VARCHAR(64) NOT NULL,
    address       TEXT        NOT NULL,
    tracking_number VARCHAR(64),
    estimated_delivery TIMESTAMPTZ,
    status        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_shipments_order ON shipments (order_id);
