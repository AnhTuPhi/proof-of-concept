CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    event_type    VARCHAR(64)  NOT NULL,
    aggregate_id  VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_processed_events_aggregate ON processed_events (aggregate_id);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
