-- CDC path: plain products table. Debezium tails the WAL — no app-level outbox needed.
CREATE TABLE IF NOT EXISTS sync_cdc.products (
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

-- REPLICA IDENTITY FULL is needed so DELETEs include all old-row data in the WAL.
-- Without this, Debezium can't tell ES which doc was deleted.
ALTER TABLE sync_cdc.products REPLICA IDENTITY FULL;

-- Publication used by the embedded Debezium engine.
-- DROP/CREATE on every startup is fine — Debezium is robust to it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'espoc_sync_cdc_pub') THEN
        EXECUTE 'CREATE PUBLICATION espoc_sync_cdc_pub FOR TABLE sync_cdc.products';
    END IF;
END $$;
