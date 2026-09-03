# ISSUE — Why this POC exists

## The core problem

Teams adopt Kafka expecting a "durable message queue" and wire it up with the
Quickstart defaults. Those defaults are tuned for a laptop demo, not for money,
inventory, or customer-facing state. The gap does not show up in dev — it shows
up in production, months later, as:

- Orders that were charged but never shipped (or shipped twice).
- Read models (search, dashboards) that drift out of sync with the source of truth.
- Consumer groups that freeze for seconds on every deploy.
- "Lost" events that were actually never published because a DB commit and a
  Kafka send were two separate operations and only one succeeded.
- One malformed message that wedges an entire partition.

None of these are Kafka bugs. They are the predictable result of not understanding
**delivery semantics, offset ownership, transactional boundaries, rebalancing, and
schema evolution**. This repository is a set of runnable proofs — one module per
failure class — that show the trap firing and the fix holding.

## What we are actually protecting

| Asset | Failure if we get it wrong | Business impact |
|---|---|---|
| **Money movement** (payments, charges) | Duplicate or lost payment events | Double-charge / revenue leak |
| **Inventory counts** | Reserve/release events lost or reordered | Oversell, stuck stock |
| **Order state machine** | Read model diverges from write model | Support tickets, wrong dashboards |
| **Event completeness** | DB says order exists, Kafka never got the event | Ghost / missing downstream work |
| **Availability during deploys** | Rebalance storms, stuck partitions | Latency spikes, SLA breach |
| **Contract stability** | A "harmless" schema change breaks consumers | Cross-team outage |

The single invariant across all of it: **every business fact is either fully
committed and observable everywhere, or not at all — never half-done.**

## The sub-issues (one per module)

Each module isolates exactly one hard question. The full engineering answer for
each lives in [TECHNICAL.md](TECHNICAL.md).

| # | Module | The hard question it answers |
|---|---|---|
| 01 | idempotent-producer | Can a producer lose or duplicate a message when a broker leader fails? |
| 02 | transactions | Can I write to two topics **and** commit the input offset atomically? |
| 03 | offset-management | Who owns "this message is done" — the broker's auto-commit, or my code? |
| 04 | dlq-poison-message | How does one bad message *not* halt the whole partition? |
| 05 | rebalancing-backpressure | Why does every deploy pause my consumers, and how do I apply backpressure without being kicked out of the group? |
| 06 | outbox-pattern | How do I change my DB **and** publish an event without a distributed transaction? |
| 07 | saga-orchestration | How do I coordinate a multi-service workflow with no global transaction and still roll back on failure? |
| 08 | cqrs-projection | How do I build a query model from events that arrive out of order? |
| 09 | streams-windowing | How do I aggregate over time without silently dropping late data? |
| 10 | streams-joins | How do I enrich/join streams without silently producing wrong results? |
| 11 | schema-registry-avro | How do I evolve an event contract without breaking existing consumers? |
| 12 | cdc-pipeline | How do I get DB changes into Kafka and downstream stores with zero app code? |

## What "done" looks like

A reviewer can, for every module:

1. Reproduce the **failure** with the Quickstart-style config (see the "Bonus"
   section of the [README](README.md)).
2. Reproduce the **fix** holding under the same fault injection.
3. Read the inline comments explaining *why* each setting is set the way it is.

This is a teaching/reference artifact, not a product. Its success metric is that
an engineer who reads it stops shipping the anti-patterns in the left column of
the table above.

## Out of scope (deliberately)

- Multi-region / DR (MirrorMaker 2) — config-only, not a Spring app.
- Security (mTLS, ACLs, SASL) — orthogonal; assumed handled at the platform layer.
- Throughput benchmarking — the knobs are exposed (module 01) but no JMH rig ships.
- Managed-Kafka specifics (MSK/Confluent Cloud) — patterns are portable; ops differ.

See [CONSISTENCY.md](CONSISTENCY.md) for how these guarantees behave when you scale
the apps horizontally across Kubernetes pods or VMs.
