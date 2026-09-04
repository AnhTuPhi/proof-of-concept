-- Per-POC schemas. Each POC owns its own schema so they can coexist without naming clashes.
CREATE SCHEMA IF NOT EXISTS sync_naive;
CREATE SCHEMA IF NOT EXISTS sync_outbox;
CREATE SCHEMA IF NOT EXISTS sync_cdc;
CREATE SCHEMA IF NOT EXISTS pagination;
CREATE SCHEMA IF NOT EXISTS reindex;
CREATE SCHEMA IF NOT EXISTS bulk;
CREATE SCHEMA IF NOT EXISTS vietnamese;
CREATE SCHEMA IF NOT EXISTS relevance;
CREATE SCHEMA IF NOT EXISTS autocomplete;
CREATE SCHEMA IF NOT EXISTS faceted;
CREATE SCHEMA IF NOT EXISTS hybrid;
CREATE SCHEMA IF NOT EXISTS consistency;
CREATE SCHEMA IF NOT EXISTS shard_sizing;
CREATE SCHEMA IF NOT EXISTS observability;
CREATE SCHEMA IF NOT EXISTS gotchas;

-- A REPLICA IDENTITY FULL is required on tables Debezium captures so updates carry full old-row values.
-- Each POC will set this on its own tables via Flyway.
