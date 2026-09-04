CREATE TABLE IF NOT EXISTS consistency.products (
    id          VARCHAR(40) PRIMARY KEY,
    sku         VARCHAR(64) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    price_cents BIGINT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
