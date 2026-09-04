# Sync & consistency

> The category where ES and the DB silently disagree, and you find out from a customer support ticket.

Two POCs:
- [db-to-es-sync-poc](../db-to-es-sync-poc/) — three sync strategies side-by-side
- [es-eventual-consistency-poc](../es-eventual-consistency-poc/) — read-your-writes

## The shared problem

You have a Postgres row. You want it to also exist in Elasticsearch so it's searchable. Question: how does the ES copy get there, and what happens when something goes wrong?

This is the **#1 ES production headache** because the failure modes are all silent. Your app keeps working — until somebody searches for a product they just created and it's not there. Or worse: it's there but with the wrong price.

## Three strategies, three honesty levels

### 1. Dual-write (the naive way — and how 80% of codebases start)

```java
@Transactional
public void updateProduct(Product p) {
    productRepo.save(p);        // hits Postgres
    esClient.index(...);        // hits Elasticsearch
}
```

Fails in two ways nobody handles:

1. **Postgres commits, ES call fails** → DB has new data, ES does not. Drift forever.
2. **Postgres rolls back, ES call already succeeded** → ES has data that doesn't exist in DB. Phantom data, queries return rows that 404 when clicked.

People paper over (1) with retries inside the transaction. That doesn't help, because:
- The retry still happens inside the DB transaction's time budget.
- If the retry succeeds but the DB commits *later*, ES is briefly ahead.
- If ES retry exhausts and you throw → DB rolls back → ES still has the partial write from earlier attempts (if a partial succeeded).

**Verdict**: works for small projects and demos. Don't ship it at any real scale.

The POC has this implementation explicitly so you can poke it and watch it break.

### 2. Transactional outbox + Kafka → ES (the right answer for most teams)

```
┌──────────────┐  same TX  ┌──────────────┐
│  products    │ ────────► │   outbox     │
└──────────────┘           └──────┬───────┘
                                  │ polled / via Debezium
                                  ▼
                            ┌──────────────┐
                            │    Kafka     │
                            └──────┬───────┘
                                  │
                                  ▼
                            ┌──────────────┐
                            │ ES indexer   │
                            └──────────────┘
```

Properties:
- Write to DB and outbox **in the same transaction**. They commit or roll back together.
- A separate worker reads the outbox and publishes to Kafka.
- A consumer reads Kafka and writes to ES with **idempotent upsert**.
- Failures retry through Kafka's standard delivery semantics; you get **at-least-once** with idempotent application.

**The honest costs:**
- Two extra systems (Kafka + indexer).
- Some lag (typically seconds, sometimes minutes if the indexer falls behind).
- Idempotency requires every event to carry the entity's full new state (or use ES `version_type: external` with a monotonic version).

### 3. Debezium CDC → ES (the least-app-code answer)

Debezium reads the Postgres WAL and emits change events directly to Kafka. No outbox table; the WAL *is* the outbox. App code shrinks back to plain `productRepo.save()`.

Properties:
- App is unaware of ES.
- Lag is very low (Debezium tails the WAL).
- Schema evolution is easy (Debezium captures DDL too).

**The honest costs:**
- Requires `wal_level=logical` and replication slots — affects DB ops.
- Replication slots that fall behind hold WAL on disk → can fill your Postgres disk.
- Event shape is the *database row*, not your *domain event* — sometimes that's wrong (e.g. composite events spanning two tables need a join, which Debezium doesn't do).

### Which to pick

| Symptom | Pick |
|---|---|
| "We have <10k events/day, single service, want simple" | Outbox |
| "We have a polyglot system, many services, want decoupling" | Outbox |
| "We don't want to change app code, ops team owns Postgres" | CDC |
| "We need domain events, not table rows" | Outbox |
| "We're a small team that just needs it to work" | Outbox |

CDC is great but the "WAL fills disk" failure mode is operationally aggressive. The POC shows both and you can pick.

## Read-your-writes

Even with correct sync, ES is *eventually* consistent. Default refresh is 1 second. If a user creates a product and immediately searches for it, they see nothing for ~1 second.

Three solutions:

### `refresh=wait_for` on the write

```java
esClient.index(i -> i.index("products").id(p.id())
    .document(p)
    .refresh(Refresh.WaitFor));
```

ES holds the write until the next refresh cycle picks it up, *then* responds. Subsequent reads see it.

Pros: simple, correct.
Cons: write latency = up to 1 second. Bad if you batch.

### Force a refresh after the write

```java
esClient.indices().refresh(r -> r.index("products"));
```

Brute force. Don't use in bulk paths — refresh is expensive (creates a new segment).

### Read-through to DB on miss

```
client searches ES
  → finds it: return
  → doesn't find but should (just-created flag): read from DB, return
```

POC implements all three. The right answer is usually #1 for single-document writes and #3 for cases where "just created" is detectable from the client side.

## Versioning to avoid out-of-order writes

When events arrive out-of-order (Kafka rebalance, retry storms), naively applying them causes the indexed doc to flip-flop. Use:

```java
esClient.index(i -> i.index("products").id(p.id())
    .document(p)
    .version(p.updatedAtMillis())
    .versionType(VersionType.External));
```

ES rejects writes whose version is ≤ the current. The POC demonstrates the bug (intentional out-of-order events) and the fix.
