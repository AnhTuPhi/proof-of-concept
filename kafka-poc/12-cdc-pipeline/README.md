# 12 - CDC Pipeline (Oracle → Kafka → Elasticsearch)

End-to-end change data capture without writing any consumer code.

```
   Oracle (LogMiner / supplemental logging)
        │
        ▼
   Debezium Oracle Source Connector
        │  (Outbox Event Router SMT splits the outbox table
        │   into per-aggregate topics)
        ▼
   Kafka topics: Order.events.v1, cdc.APPUSER.ORDERS, ...
        │
        ▼
   Elasticsearch Sink Connector  (idempotent upsert by document id)
        │
        ▼
   Elasticsearch index: orders, payments, inventory
```

## Why this beats hand-rolled consumers

- **Zero application code** in the data path. Failure modes are operational, not bugs in your code.
- **Outbox routing SMT** turns one outbox table into many topic streams without your service knowing.
- **Idempotent ES upsert** via the `key.ignore=false` + document-id strategy survives DLQ replays.
- **DLQ topic** on the sink connector captures unmappable docs without halting the pipeline.

## Production checklist before you turn this on

1. `ENABLE_ARCHIVELOG=true` on Oracle. Without it, LogMiner cannot read past commits.
2. `ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS` on every table you stream.
3. Sized `schema-history` topic with `cleanup.policy=compact` and `min.cleanable.dirty.ratio=0.01`.
4. Monitor Debezium connector lag with the JMX `MilliSecondsBehindSource` metric — that's your data-freshness SLO.
5. Run Connect in distributed mode with at least 2 workers. The single-worker default loses state on crash.
6. Pin Debezium `database.history.skip.unparseable.ddl=false` so a DDL surprise pages you instead of silently corrupting history.

## Register both connectors

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  --data @connectors/01-debezium-oracle-source.json

curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  --data @connectors/02-elasticsearch-sink.json
```

## Verify

```bash
# What's running and is it healthy?
curl -s http://localhost:8083/connectors?expand=status | jq

# Stream the source-side CDC topic
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic Order.events.v1 \
  --from-beginning --max-messages 5

# Check ES picked them up
curl -s http://localhost:9200/orders/_search?pretty
```
