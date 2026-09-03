CREATE TABLE orders (
    id              UUID            PRIMARY KEY,
    customer_id     VARCHAR(64)     NOT NULL,
    product_sku     VARCHAR(64)     NOT NULL,
    quantity        INTEGER         NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(19, 4)  NOT NULL CHECK (unit_price > 0),
    total_amount    NUMERIC(19, 4)  NOT NULL CHECK (total_amount > 0),
    status          VARCHAR(32)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status      ON orders (status);
