# ISSUE — What this suite exists to solve

> One line: **teams keep shipping Elasticsearch as if it were a search-flavored database. It isn't, and the failure modes are silent.**

The `es-poc-suite` is not thirteen unrelated demos. Every POC is a slice of the same umbrella problem: a Spring Boot app talking to Elasticsearch works fine in dev, then breaks in a way that never shows up in the CI, is invisible in logs, and gets triaged as "search is weird sometimes" until users complain loudly enough to be believed.

This document is the catalog of that pain — what breaks, when it breaks, why it breaks, and which POC owns each fix.

---

## The umbrella issue

You have a Postgres row. You want it to be searchable. You wire an ES client, index on write, query on read, ship it.

Then, at scale, **five categories of failure** appear. All of them are silent by default.

| Category | The failure the user actually sees | What the ops team sees |
|---|---|---|
| **Sync drift** | "I created a product; it doesn't come up in search." | ES doc count ≠ DB row count. Nobody knows when it diverged. |
| **Read staleness** | "I just saved this. Why is the app showing old data?" | ES refresh window (1s default) is longer than user patience. |
| **Deep pagination collapse** | "Page 501: `Result window is too large`." | `from+size` at depth is O(from×shards). ES trips its own guard. |
| **Ingest starvation** | "Nightly import taking 8 hours; should be 15 minutes." | Refresh + replica + single-doc writes = 100× slower than tuned bulk. |
| **Relevance rot** | "Search for `iPhone 15` — the top hit is a phone case." | Nobody defined what "relevant" means; no eval; nobody can prove a change helped. |
| **Ops surprises** | Cluster yellow at 3am. Recovery takes 45 minutes. | Shard count wrong. Mapping exploded. Heap crossed the compressed-oops cliff. |
| **Language mismatch** | Vietnamese users query `dien thoai`, get 0 hits for `điện thoại`. | Default analyzer tokenizes VN badly. Nobody flagged it. |

Each row is a real production incident somebody has lived through. The suite exists so the next team doesn't have to.

---

## What we are protecting

Three assets, in order of blast radius:

### 1. Data correctness — Postgres ↔ ES agreement

The Postgres row is the source of truth. Every ES doc should be a **derivable projection** of one or more rows. If the projection drifts, the app is *silently wrong* — search returns the old price, the removed listing, the wrong owner. The user takes an action based on stale data and it lands in production.

Protecting this means: every write path either commits both sides atomically, or has a **detectable, bounded** repair mechanism.

**Owned by**: [db-to-es-sync-poc](./db-to-es-sync-poc/), [es-eventual-consistency-poc](./es-eventual-consistency-poc/), [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/).

### 2. Availability — reads and writes under load, migrations, and failure

The ES cluster is a *stateful, shared* resource. A single bad query can pin a node; a bad mapping change requires downtime; a naive bulk load will refresh itself into merge death. The failure isn't "slower search" — it's **cascading**: one query slows the coordinator, coordinator queues fill, all queries slow, replicas lag, indexing lags, users see empty results.

Protecting this means: bounded resource use per query, migrations that never drop writes, ingest that respects the merge scheduler.

**Owned by**: [es-deep-pagination-poc](./es-deep-pagination-poc/), [es-bulk-indexing-poc](./es-bulk-indexing-poc/), [es-shard-sizing-poc](./es-shard-sizing-poc/), [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/), [es-observability-poc](./es-observability-poc/), [es-gotchas-poc](./es-gotchas-poc/).

### 3. Search quality — the "why does my search box suck" contract

The set of returned docs is table-stakes. The **ordering** is the product. Users look at the top 3. If the right doc is at rank 12, the search failed even though the doc is technically "found". Quality regressions ship silently because nobody is measuring.

Protecting this means: an explicit relevance definition, a judged dataset, an eval harness, and language-aware analysis that doesn't lose 40% of your users' queries to diacritic-strip.

**Owned by**: [es-relevance-tuning-poc](./es-relevance-tuning-poc/), [es-vietnamese-search-poc](./es-vietnamese-search-poc/), [es-autocomplete-poc](./es-autocomplete-poc/), [es-hybrid-search-poc](./es-hybrid-search-poc/), [es-faceted-search-poc](./es-faceted-search-poc/).

---

## Concrete sub-problems (mapped to POCs)

Each row is a *specific* bug that will happen to you if you don't do something about it. This is the punch list.

### Sync & consistency

| # | Sub-problem | Trigger | Impact | POC |
|---|---|---|---|---|
| S1 | Dual-write drift: DB commits, ES call fails, no repair | Any transient ES error | Permanent silent drift | [db-to-es-sync-poc](./db-to-es-sync-poc/) |
| S2 | Dual-write phantom: ES writes, DB rolls back | Exception after ES call, before commit | ES has non-existent rows | [db-to-es-sync-poc](./db-to-es-sync-poc/) |
| S3 | Out-of-order events flip a doc backwards | Kafka rebalance, retry storm | Doc alternates between old/new state | [es-eventual-consistency-poc](./es-eventual-consistency-poc/) |
| S4 | Read-your-writes miss: user creates → searches → nothing | Default 1s refresh | "Where's the thing I just created?" | [es-eventual-consistency-poc](./es-eventual-consistency-poc/) |
| S5 | Mapping change needs downtime | Any non-additive mapping edit | Reindex outage or drop-writes window | [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/) |
| S6 | Debezium replication slot fills disk | Consumer dies unnoticed | Postgres runs out of space | [db-to-es-sync-poc](./db-to-es-sync-poc/) |

### Scale & ops

| # | Sub-problem | Trigger | Impact | POC |
|---|---|---|---|---|
| O1 | `from+size` collapses past 10k | Deep pagination / export | `Result window is too large` | [es-deep-pagination-poc](./es-deep-pagination-poc/) |
| O2 | `from+size` slow before it collapses | Depth grows | Latency scales with `from × shards` | [es-deep-pagination-poc](./es-deep-pagination-poc/) |
| O3 | Bulk ingest 100× slower than needed | Default refresh + replicas during load | Multi-hour imports; merge storms | [es-bulk-indexing-poc](./es-bulk-indexing-poc/) |
| O4 | Too many small shards | Fixed shard count on tiny indexes | Cluster-state bloat, slow recovery | [es-shard-sizing-poc](./es-shard-sizing-poc/) |
| O5 | Too few huge shards | Under-sharded time-series | Slow queries, painful reallocation | [es-shard-sizing-poc](./es-shard-sizing-poc/) |
| O6 | Mapping explosion | Dynamic fields with unbounded keys | Cluster state → minutes to load | [es-shard-sizing-poc](./es-shard-sizing-poc/), [es-gotchas-poc](./es-gotchas-poc/) |
| O7 | Fielddata on `text` OOMs the JVM | Sort/agg on `text` | Heap climbs, never comes back | [es-gotchas-poc](./es-gotchas-poc/) |
| O8 | Leading-wildcard pins a node | `*foo` in the search box | Whole-cluster CPU spike | [es-gotchas-poc](./es-gotchas-poc/) |
| O9 | `_id` collision silently overwrites | Resumed indexer, duplicate keys | Older content replaces newer | [es-gotchas-poc](./es-gotchas-poc/) |
| O10 | Heap > 32 GB loses compressed oops | Naive JVM sizing | *Effective* heap shrinks past the cliff | [es-gotchas-poc](./es-gotchas-poc/) |
| O11 | No visibility into slow queries | No slow log, no profile | "Search is slow sometimes" — untriageable | [es-observability-poc](./es-observability-poc/) |

### Search quality

| # | Sub-problem | Trigger | Impact | POC |
|---|---|---|---|---|
| Q1 | Relevance ships blind | Any code change to query | Regressions unnoticed for weeks | [es-relevance-tuning-poc](./es-relevance-tuning-poc/) |
| Q2 | Vietnamese diacritic mismatch | User types unaccented VN | 40%+ zero-result rate | [es-vietnamese-search-poc](./es-vietnamese-search-poc/) |
| Q3 | Autocomplete p99 is too slow | Wrong technique for the workload | Users type faster than we suggest | [es-autocomplete-poc](./es-autocomplete-poc/) |
| Q4 | Semantic queries miss ("something to make coffee") | BM25-only search | User has to guess our vocabulary | [es-hybrid-search-poc](./es-hybrid-search-poc/) |
| Q5 | Facet multi-select shows wrong counts | Filter applied to aggs directly | Selecting "Apple" hides all other brands | [es-faceted-search-poc](./es-faceted-search-poc/) |

---

## Why this list, not others

The POCs cover **failure modes that are silent, non-obvious, and default-off**. We intentionally do *not* cover:

- **Cluster provisioning** — Elastic Cloud / self-hosted trade-offs. Well-documented, not our fight.
- **Security** — TLS, RBAC, field-level security. Important, but the failure is loud (auth error) not silent.
- **Analyzer plugin authoring** — one team in a hundred needs this.
- **ES SQL / EQL / Painless** — layer we haven't seen bite anyone in the DAccount context.

The bar for inclusion is: *"a mid-level Java engineer, following the ES docs faithfully, will ship this bug."* Everything else is out of scope.

---

## How to use this document

- **On a new project**: read this once, use the sub-problem table as an intake checklist for the design review.
- **On an incident**: search the table for the symptom column, jump to the POC.
- **Onboarding**: the first day is [architecture.md](./docs/architecture.md); the second day is walking the ISSUE table and running two or three POCs whose sub-problems your team has hit.

Companion docs:
- [TECHNICAL.md](./TECHNICAL.md) — for each POC: hard problem, solution shape, tech by responsibility, tech debt.
- [CONSISTENCY.md](./CONSISTENCY.md) — what happens when the app runs as N pods behind a k8s service or on multiple VMs.
- [demo.html](./demo.html) — interactive walkthrough of the flows.
