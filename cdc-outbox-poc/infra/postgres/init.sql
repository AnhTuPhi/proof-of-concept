-- The `cdc` database is created by POSTGRES_DB. We additionally need a
-- separate `notifications` database for the consumer's dedup ledger.
-- Both share the cluster but live in independent schemas.

CREATE USER notif WITH PASSWORD 'notif';
CREATE DATABASE notifications OWNER notif;
GRANT ALL PRIVILEGES ON DATABASE notifications TO notif;

-- Debezium needs REPLICATION privileges on the source database.
ALTER USER cdc WITH REPLICATION;
