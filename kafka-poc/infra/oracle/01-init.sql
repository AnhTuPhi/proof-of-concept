-- Run inside FREEPDB1 (default startup script context).
-- Creates application user + outbox infrastructure + sample domain tables.

ALTER SESSION SET CONTAINER = FREEPDB1;

-- Application user
CREATE USER appuser IDENTIFIED BY "AppUser123"
  DEFAULT TABLESPACE USERS
  QUOTA UNLIMITED ON USERS;

GRANT CONNECT, RESOURCE, CREATE SESSION, CREATE TABLE, CREATE SEQUENCE,
      CREATE PROCEDURE, CREATE TRIGGER, CREATE VIEW TO appuser;

-- Debezium user with LogMiner privileges
CREATE USER debezium IDENTIFIED BY "Debezium123"
  DEFAULT TABLESPACE USERS
  QUOTA UNLIMITED ON USERS;

GRANT CREATE SESSION TO debezium;
GRANT SET CONTAINER TO debezium;
GRANT SELECT ON V_$DATABASE TO debezium;
GRANT FLASHBACK ANY TABLE TO debezium;
GRANT SELECT ANY TABLE TO debezium;
GRANT SELECT_CATALOG_ROLE TO debezium;
GRANT EXECUTE_CATALOG_ROLE TO debezium;
GRANT SELECT ANY TRANSACTION TO debezium;
GRANT LOGMINING TO debezium;
GRANT CREATE TABLE TO debezium;
GRANT LOCK ANY TABLE TO debezium;
GRANT CREATE SEQUENCE TO debezium;
GRANT EXECUTE ON DBMS_LOGMNR TO debezium;
GRANT EXECUTE ON DBMS_LOGMNR_D TO debezium;
GRANT SELECT ON V_$LOG TO debezium;
GRANT SELECT ON V_$LOG_HISTORY TO debezium;
GRANT SELECT ON V_$LOGMNR_LOGS TO debezium;
GRANT SELECT ON V_$LOGMNR_CONTENTS TO debezium;
GRANT SELECT ON V_$LOGMNR_PARAMETERS TO debezium;
GRANT SELECT ON V_$LOGFILE TO debezium;
GRANT SELECT ON V_$ARCHIVED_LOG TO debezium;
GRANT SELECT ON V_$ARCHIVE_DEST_STATUS TO debezium;
GRANT SELECT ON V_$TRANSACTION TO debezium;

-- Supplemental logging required for Debezium
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;

-- =========================================================
-- Domain schema (appuser)
-- =========================================================
ALTER SESSION SET CURRENT_SCHEMA = appuser;

-- ORDERS aggregate
CREATE TABLE appuser.orders (
  order_id     VARCHAR2(36)  PRIMARY KEY,
  customer_id  VARCHAR2(36)  NOT NULL,
  status       VARCHAR2(20)  NOT NULL,
  total_amount NUMBER(18,2)  NOT NULL,
  currency     VARCHAR2(3)   DEFAULT 'USD' NOT NULL,
  created_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  version      NUMBER        DEFAULT 0 NOT NULL
);
CREATE INDEX appuser.idx_orders_customer ON appuser.orders(customer_id);
CREATE INDEX appuser.idx_orders_status   ON appuser.orders(status);
ALTER TABLE appuser.orders ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- PAYMENTS (for Saga)
CREATE TABLE appuser.payments (
  payment_id   VARCHAR2(36)  PRIMARY KEY,
  order_id     VARCHAR2(36)  NOT NULL,
  amount       NUMBER(18,2)  NOT NULL,
  status       VARCHAR2(20)  NOT NULL,
  created_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX appuser.idx_payments_order ON appuser.payments(order_id);
ALTER TABLE appuser.payments ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- INVENTORY (for Saga)
CREATE TABLE appuser.inventory (
  sku          VARCHAR2(64)  PRIMARY KEY,
  available    NUMBER        NOT NULL,
  reserved     NUMBER        DEFAULT 0 NOT NULL,
  updated_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE appuser.inventory ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

INSERT INTO appuser.inventory(sku, available) VALUES ('SKU-001', 100);
INSERT INTO appuser.inventory(sku, available) VALUES ('SKU-002', 50);
INSERT INTO appuser.inventory(sku, available) VALUES ('SKU-003', 200);

-- =========================================================
-- TRANSACTIONAL OUTBOX
-- The single source of truth for "events we promised to publish".
-- Producer writes to this table inside the SAME transaction as
-- the domain change; a poller (or Debezium) ships them to Kafka.
-- =========================================================
CREATE TABLE appuser.outbox (
  id              VARCHAR2(36)   PRIMARY KEY,
  aggregate_type  VARCHAR2(64)   NOT NULL,
  aggregate_id    VARCHAR2(64)   NOT NULL,
  event_type      VARCHAR2(64)   NOT NULL,
  payload         CLOB           NOT NULL,
  headers         CLOB,
  created_at      TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
  published_at    TIMESTAMP,
  partition_key   VARCHAR2(128)
);
CREATE INDEX appuser.idx_outbox_unpublished
  ON appuser.outbox(published_at, created_at);
ALTER TABLE appuser.outbox ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- =========================================================
-- PROCESSED MESSAGES (idempotent consumer table)
-- For consumers that need true exactly-once side effects.
-- =========================================================
CREATE TABLE appuser.processed_messages (
  message_id   VARCHAR2(128) PRIMARY KEY,
  topic        VARCHAR2(128) NOT NULL,
  consumer_id  VARCHAR2(128) NOT NULL,
  processed_at TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON appuser.orders             TO debezium;
GRANT SELECT, INSERT, UPDATE, DELETE ON appuser.outbox             TO debezium;
GRANT SELECT, INSERT, UPDATE, DELETE ON appuser.payments           TO debezium;
GRANT SELECT, INSERT, UPDATE, DELETE ON appuser.inventory          TO debezium;
GRANT SELECT, INSERT, UPDATE, DELETE ON appuser.processed_messages TO debezium;

COMMIT;
