# Operations runbook

## Health checks

| Endpoint                                        | Purpose                              |
|-------------------------------------------------|--------------------------------------|
| `GET http://localhost:8080/actuator/health`     | order-service overall                |
| `GET http://localhost:8081/actuator/health`     | notification-service overall         |
| `GET http://localhost:8080/actuator/prometheus` | order-service metrics for scraping   |
| `GET http://localhost:8083/connectors`          | Debezium: list connectors            |
| `GET http://localhost:8083/connectors/order-outbox-connector/status` | Debezium: per-connector + per-task status |

## Key metrics to alert on

- `debezium_connector_outbox_event_router_*` — events emitted, errors
- `kafka_consumer_lag_records` on `outbox.event.Order` (consumer side)
- `outbox_events` row count growth rate (anomaly = backed-up Debezium)
- `processed_events` insert rate (consumer throughput)
- Connector task state != RUNNING for > 60 s
- Postgres `pg_replication_slots.confirmed_flush_lsn` falling behind `pg_current_wal_lsn`

## Common operational tasks

### Connector keeps failing on startup

```bash
curl -s http://localhost:8083/connectors/order-outbox-connector/status | jq '.tasks[].trace'
```

Most common causes:

| Error                                                           | Cause / Fix                                                                                 |
|-----------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `wal_level must be set to logical`                              | Postgres started without `-c wal_level=logical`. Restart with the flag.                     |
| `permission denied to create replication slot`                  | DB user lacks `REPLICATION`. `ALTER USER cdc WITH REPLICATION;`                             |
| `column "payload" not found` / SMT errors                       | Outbox table schema drifted. Re-check the migrations match `outbox-connector.json` fields.  |
| `Replication slot "..." already exists`                         | Stale slot from a previous run. `SELECT pg_drop_replication_slot('debezium_order_outbox');` |
| `publication "..." does not exist`                              | Set `publication.autocreate.mode=filtered` (already configured here).                       |

### Replication slot growing unbounded

```sql
SELECT slot_name, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS lag
FROM pg_replication_slots;
```

If `lag` is steadily growing, the consumer of the slot (Debezium) isn't keeping up. Causes:

- Debezium is down or paused
- Outbox INSERT rate exceeds Debezium throughput → increase `max.batch.size` / `max.queue.size`
- Idle producer with no heartbeat → already mitigated by `heartbeat.interval.ms=10000` in the connector config

If you need to **drop** a stale slot (e.g. after a debug session):

```sql
SELECT pg_drop_replication_slot('debezium_order_outbox');
```

This is destructive — you lose any uncaptured events between the slot's position and HEAD.

### Consumer lag spikes

```bash
docker compose exec kafka kafka-consumer-groups \
  --bootstrap-server kafka:29092 \
  --describe --group notification-service
```

Mitigations:

- Scale partitions for the topic (requires producer-side awareness).
- Add consumer concurrency: in `KafkaConfig.kafkaListenerContainerFactory`, set `factory.setConcurrency(N)`.
- Profile the handler — most lag in a JSON+JPA consumer comes from a slow downstream call.

### Replaying events from scratch

To re-process every outbox event since the snapshot:

```bash
# 1. Stop the consumer
docker compose stop notification-service

# 2. Reset the consumer group offset
docker compose exec kafka kafka-consumer-groups \
  --bootstrap-server kafka:29092 \
  --group notification-service \
  --topic outbox.event.Order \
  --reset-offsets --to-earliest --execute

# 3. Clear the dedup ledger (so old events are processed again)
docker compose exec postgres psql -U notif -d notifications -c "TRUNCATE processed_events;"

# 4. Restart the consumer
docker compose start notification-service
```

### Outbox cleanup is too aggressive

If you suspect events are being deleted before Debezium captures them:

```sql
-- Check oldest unprocessed (still in WAL) outbox row
SELECT MIN(created_at) FROM outbox_events;

-- Compare to slot lag (in seconds)
SELECT EXTRACT(EPOCH FROM (now() - pg_last_committed_xact()::text::timestamptz)) AS slot_lag_seconds;
```

Increase `app.outbox.retention` in `order-service`'s `application.yml`. Default is `P7D` (7 days).

## Production-grade things this PoC skips

1. **Heartbeat action query.** For an idle producer, the slot won't advance. In production, configure `heartbeat.action.query` to write to a dedicated `debezium_heartbeat` table NOT under the SMT — typically by either:
   - Running a second connector without the SMT, or
   - Using `transforms.outbox.table.expand.json.payload=false` + a predicate to skip the heartbeat table.
2. **Connector auto-registration.** This PoC registers the connector via a manual script. In production, use Debezium UI, Kubernetes Operator (Strimzi), or a CI job on startup.
3. **Schema Registry + Avro.** JSON works but is unversioned. Avro + Schema Registry catches breaking schema changes at publish time.
4. **Authentication everywhere.** SASL on Kafka, SSL on Postgres, OAuth2 in front of the REST APIs.
5. **Multi-broker Kafka.** A single broker is fine for the PoC; production needs ≥3 with RF=3.
