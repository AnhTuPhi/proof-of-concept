# POC 05 — Auth: JWT + mTLS + HTTP Backend + Postgres ACL

> **Goal:** Show all four mainstream auth patterns for EMQX, side-by-side, and the multi-tenant ACL pattern that goes with them.

## What's wired up

| Mode | Who decides allow/deny | Best for |
|---|---|---|
| **Postgres `password_based`** | EMQX queries Postgres directly with the username, hashes the supplied password, compares. | Default for small/medium device fleets. Simple, no extra service. |
| **JWT (HS256)** | EMQX validates the token signature with a shared HMAC secret. Claims `username`, `tenant_id`, optional inline `acl`. | Stateless. Token TTL is your revocation window. Auth cost per CONNECT is just an HMAC verify - cheapest. |
| **HTTP backend** | EMQX POSTs `{username, password, clientid, peerhost}` to this Spring app, expects `{result: allow|deny, ...}`. | Your identity lives in something EMQX can't reach (custom auth service, SaaS IDP). |
| **mTLS** | TLS handshake itself. Client cert CN becomes the MQTT username. | The gold standard for device identity - hardware-backed, no shared secret on the wire. |

You'd typically combine **mTLS + JWT**: TLS proves identity, JWT carries claims (tenant, role). Or **mTLS alone** when the cert CN is enough.

## Run

```bash
# 1. Get a JWT
curl -X POST 'localhost:8105/provision/jwt?deviceId=device-001&tenant=tenant-a'
# {"token":"eyJ...", "deviceId":"device-001", "tenantId":"tenant-a", "expiresAtEpochMillis":...}

# 2. Connect with that JWT as MQTT password (any MQTT client; here using mosquitto_pub):
TOKEN=$(curl -sX POST 'localhost:8105/provision/jwt?deviceId=device-001&tenant=tenant-a' | jq -r .token)
mosquitto_pub -h localhost -p 1883 -i device-001 -u device-001 -P "$TOKEN" \
  -t tenant/tenant-a/devices/device-001/telemetry \
  -m '{"temp":21}'

# 3. Try cross-tenant access - SHOULD be denied by ACL:
mosquitto_pub -h localhost -p 1883 -i device-001 -u device-001 -P "$TOKEN" \
  -t tenant/tenant-b/devices/device-002/telemetry \
  -m '{"x":1}'
# -> publish rejected

# 4. mTLS path - issue a cert, then connect with it
curl -X POST 'localhost:8105/provision/cert?deviceId=device-001'
# {"deviceId":"device-001", "certPath":"/tmp/emqx-poc-certs/device-001.crt", ...}

mosquitto_pub -h localhost -p 8883 -i device-001 \
  --cafile /tmp/emqx-poc-certs/ca.crt \
  --cert /tmp/emqx-poc-certs/device-001.crt \
  --key /tmp/emqx-poc-certs/device-001.key \
  -t tenant/tenant-a/devices/device-001/telemetry \
  -m '{"via":"mtls"}'
```

(You'll need to also put the same `ca.crt` into the broker's trust path - see infra/emqx/certs/ in the repo and use the provided script.)

## Multi-tenant ACL pattern

This is the part most teams get wrong. The pattern:

```
tenant/{tenant_id}/devices/{device_id}/...
```

ACL rule (in `mqtt_acl` table):

```sql
INSERT INTO mqtt_acl (username, action, permission, topic) VALUES
  ('device-001', 'publish',   'allow', 'tenant/tenant-a/devices/device-001/#'),
  ('device-001', 'subscribe', 'allow', 'tenant/tenant-a/devices/device-001/cmd/#');
```

With **default-deny** (`authorization.no_match = deny` in emqx.conf), cross-tenant access is implicitly denied — no need for "deny" rules. This is much safer than allow-list-everything-then-deny-the-bad-stuff.

**Performance**: the ACL backend is hit on every publish/subscribe. EMQX caches results per connection (`authorization.cache`). With cache: ~1µs per check. Without cache: ~1ms (DB round-trip). **Enable the cache** even at small scale.

## JWT inline ACL (skip the DB entirely)

If you don't need DB-managed ACL, embed it in the JWT:

```json
{
  "sub": "device-001",
  "username": "device-001",
  "tenant_id": "tenant-a",
  "acl": {
    "pub": "tenant/tenant-a/devices/device-001/#",
    "sub": "tenant/tenant-a/devices/device-001/cmd/#"
  },
  "exp": 1735689600
}
```

EMQX validates the signature, reads the `acl` claim, no DB hop. Auth + authz in 50µs.

## mTLS at scale - the parts to plan

1. **Cert issuance API** - this POC ships one. In production you'd put it behind your provisioning service, and the CA key lives in a KMS.
2. **Cert rotation** - certs expire. With a fleet of 100k devices and 30-day certs, you're renewing ~3k devices/day. Devices request a new cert before expiry (typical: at 75% of TTL).
3. **OCSP / CRL** - for revocation. EMQX supports both. CRL is fine up to ~10k revoked certs; for larger, switch to OCSP stapling.
4. **TLS handshake CPU cost** - RSA-2048 handshake = ~5ms CPU. ECDSA P-256 = ~1ms. **Use ECDSA** for large fleets. Throughput on one EMQX node: ~5k TLS handshakes/sec with ECDSA, ~1k with RSA-2048.

## What "production-ready" means here

- Secret rotation for JWT HMAC (use a key id + a small set of valid keys).
- CA private key in HSM or cloud KMS - **never** on the EMQX or app host.
- ACL cache enabled with TTL ~1min (short enough to react to revocation, long enough to avoid hot DB).
- Default-deny ACL.
- TLS 1.3 only.
- Audit log on every `auth.fail` (rule engine writes to event_log table - see POC 06).
