-- =====================================================================
-- EMQX auth + ACL + rule-engine sink schema
-- =====================================================================
--
-- This file is auto-applied by Postgres on first start.
--
-- Three concerns live here:
--   1. mqtt_user / mqtt_acl  - EMQX 5.x built-in auth/ACL connector schema
--   2. device_state         - device shadow + rule-engine SQL sink target
--   3. event_log            - rule-engine "audit log" sink for every publish
--
-- Why one DB for all three:
--   In real deployments these usually split (auth in a hardened cluster,
--   telemetry in a hot store). For a POC, one DB keeps it readable.
-- =====================================================================

-- ---------- 1. AUTH ----------
CREATE TABLE IF NOT EXISTS mqtt_user (
    id              SERIAL PRIMARY KEY,
    username        VARCHAR(128) NOT NULL UNIQUE,
    password_hash   VARCHAR(256) NOT NULL,
    salt            VARCHAR(64)  NOT NULL,
    is_superuser    BOOLEAN      DEFAULT FALSE,
    tenant_id       VARCHAR(64),                       -- multi-tenant ns
    created_at      TIMESTAMPTZ  DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mqtt_user_tenant ON mqtt_user(tenant_id);

-- ---------- 2. ACL ----------
-- EMQX expects: username, ipaddr, clientid, action, permission, topic
-- We use only the columns EMQX queries to keep the index small.
CREATE TABLE IF NOT EXISTS mqtt_acl (
    id          SERIAL PRIMARY KEY,
    username    VARCHAR(128),
    clientid    VARCHAR(128),
    ipaddr      VARCHAR(64),
    action      VARCHAR(32) NOT NULL,    -- publish | subscribe | all
    permission  VARCHAR(32) NOT NULL,    -- allow | deny
    topic       VARCHAR(512) NOT NULL,   -- supports + and # wildcards
    qos         SMALLINT[],
    retain      VARCHAR(8),
    "order"     INT DEFAULT 100
);

CREATE INDEX IF NOT EXISTS idx_mqtt_acl_username ON mqtt_acl(username);
CREATE INDEX IF NOT EXISTS idx_mqtt_acl_clientid ON mqtt_acl(clientid);

-- ---------- 3. Device shadow ----------
-- Desired vs reported state (AWS IoT Device Shadow equivalent), POC 11.
-- Two JSONB blobs let you patch arbitrary state without schema changes.
CREATE TABLE IF NOT EXISTS device_state (
    device_id       VARCHAR(128) PRIMARY KEY,
    tenant_id       VARCHAR(64),
    reported        JSONB        DEFAULT '{}'::jsonb,   -- last reported from device
    desired         JSONB        DEFAULT '{}'::jsonb,   -- what backend wants
    delta           JSONB        DEFAULT '{}'::jsonb,   -- desired - reported
    version         BIGINT       DEFAULT 0,             -- monotonic; CAS on update
    reported_at     TIMESTAMPTZ,
    desired_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_device_state_tenant ON device_state(tenant_id);

-- ---------- 4. Telemetry sink ----------
-- Rule engine writes high-volume sensor data here (POC 06).
-- In production this should be TimescaleDB / ClickHouse - we keep vanilla PG.
CREATE TABLE IF NOT EXISTS telemetry (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(128) NOT NULL,
    tenant_id       VARCHAR(64),
    topic           VARCHAR(512) NOT NULL,
    payload         JSONB        NOT NULL,
    qos             SMALLINT,
    ts              TIMESTAMPTZ  DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_telemetry_device_ts ON telemetry(device_id, ts DESC);

-- ---------- 5. Audit log ----------
-- One row per significant event (connect/disconnect/auth-fail).
CREATE TABLE IF NOT EXISTS event_log (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(32) NOT NULL,
    clientid        VARCHAR(128),
    username        VARCHAR(128),
    peerhost        VARCHAR(64),
    reason          VARCHAR(128),
    ts              TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_event_log_ts ON event_log(ts DESC);

-- ---------- Seed data ----------
-- These users exist so the auth POC has something to demo immediately.
-- password_hash here is sha256(password + salt) - matches EMQX builtin scheme.
-- salt = 'demosalt' for all demo users (DO NOT do this in production).
INSERT INTO mqtt_user (username, password_hash, salt, is_superuser, tenant_id) VALUES
  -- device-001 / device-001-secret
  ('device-001', encode(digest('device-001-secret' || 'demosalt', 'sha256'), 'hex'), 'demosalt', false, 'tenant-a'),
  ('device-002', encode(digest('device-002-secret' || 'demosalt', 'sha256'), 'hex'), 'demosalt', false, 'tenant-a'),
  ('device-101', encode(digest('device-101-secret' || 'demosalt', 'sha256'), 'hex'), 'demosalt', false, 'tenant-b'),
  -- backend-svc / backend-secret (superuser - skips ACL, used by POC 06)
  ('backend-svc', encode(digest('backend-secret' || 'demosalt', 'sha256'), 'hex'), 'demosalt', true, NULL)
ON CONFLICT (username) DO NOTHING;

-- ACL rules: tenant isolation pattern from POC 05.
-- Each device may publish to tenant/{tenant_id}/devices/{username}/...
-- and subscribe to tenant/{tenant_id}/devices/{username}/cmd/#.
-- Cross-tenant access is implicitly denied (default-deny ACL).
INSERT INTO mqtt_acl (username, action, permission, topic) VALUES
  ('device-001', 'publish',   'allow', 'tenant/tenant-a/devices/device-001/#'),
  ('device-001', 'subscribe', 'allow', 'tenant/tenant-a/devices/device-001/cmd/#'),
  ('device-002', 'publish',   'allow', 'tenant/tenant-a/devices/device-002/#'),
  ('device-002', 'subscribe', 'allow', 'tenant/tenant-a/devices/device-002/cmd/#'),
  ('device-101', 'publish',   'allow', 'tenant/tenant-b/devices/device-101/#'),
  ('device-101', 'subscribe', 'allow', 'tenant/tenant-b/devices/device-101/cmd/#')
ON CONFLICT DO NOTHING;
