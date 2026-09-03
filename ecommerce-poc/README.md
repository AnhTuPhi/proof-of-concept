# ecommerce-pocs

Five focused POCs on the **hard, contended parts** of a real e-commerce
backend: inventory reservation, oversell prevention, flash-sale admission
control, coupon composition, and multi-warehouse fulfillment routing.

Each POC isolates one problem, runs it under concurrent load, and shows a
correct-and-scalable answer next to the naive answer that fails silently.

- Java 21, virtual threads
- No external dependencies — every POC runs with `mvn compile exec:java`
- Deliberately in-memory. See [`CONSISTENCY.md`](CONSISTENCY.md) for how
  each primitive maps to Redis / DB / Kafka at N-pod scale.

## The docs

| Doc | Read it for |
|-----|-------------|
| [`ISSUE.md`](ISSUE.md) | What each POC is defending against, in plain English — symptom, root cause, money at stake |
| [`TECHNICAL.md`](TECHNICAL.md) | Solution shape per POC — hard part, invariant, key tech by responsibility, tech debt |
| [`CONSISTENCY.md`](CONSISTENCY.md) | What breaks when you scale from one JVM to N pods on Kubernetes / N VMs behind a LB, and how to fix it |
| [`docs/flow.html`](docs/flow.html) | Interactive visual walkthrough of each POC — open in a browser |

## The five POCs

| # | POC | Main class | The hard part |
|---|-----|------------|---------------|
| 1 | **Inventory Reservation** | `vn.com.ipas.poc.inventory.InventoryReservationPoc` | Atomic reserve/confirm/release with TTL, no orphaned holds, no oversell under 1000 concurrent buyers for 1 unit |
| 2 | **Oversell Prevention** | `vn.com.ipas.poc.oversell.OversellPreventionPoc` | Five strategies race 1000 virtual threads against 10 units; naive oversells, four correct ones show throughput/complexity trade-offs |
| 3 | **Flash Sale** | `vn.com.ipas.poc.flashsale.FlashSalePoc` | 100k concurrent requests, 100 units, two-layer defence: admission gate + bucketed inventory |
| 4 | **Coupon Engine** | `vn.com.ipas.poc.coupon.CouponEnginePoc` | Deterministic stage ordering, symmetric stackability check, audit trail, brute-force best-combo finder |
| 5 | **Fulfillment Routing** | `vn.com.ipas.poc.fulfillment.FulfillmentRoutingPoc` | Greedy vs branch-and-bound with a `SPLIT_PENALTY` so the optimizer picks what ops actually wants |

## Running a POC

From the project root:

```bash
mvn compile

# Pick one:
mvn exec:exec@inventory
mvn exec:exec@oversell
mvn exec:exec@flashsale
mvn exec:exec@coupon
mvn exec:exec@fulfillment
```

Or directly:

```bash
mvn compile
mvn exec:java -Dexec.mainClass=vn.com.ipas.poc.inventory.InventoryReservationPoc
```

Each POC prints its scenarios, results, and takeaways to stdout. The
oversell POC additionally prints a strategy-comparison table.

## Source layout

```
src/main/java/vn/com/ipas/poc/
  common/     — Banner (section headers)
  inventory/  — POC 1
  oversell/   — POC 2 (5 strategies + a shared interface)
  flashsale/  — POC 3 (AdmissionGate + BucketedInventory)
  coupon/     — POC 4 (sealed Coupon hierarchy + engine + best-combo finder)
  fulfillment/— POC 5 (Domain + GreedyRouter + OptimalRouter)
```

## Reading order

1. Skim [`ISSUE.md`](ISSUE.md) — get the intent.
2. Pick a POC, read its main class + its supporting types (~200 LOC each).
3. Cross-reference against [`TECHNICAL.md`](TECHNICAL.md) for the "why" of
   each moving part and the tech debt list.
4. Open [`CONSISTENCY.md`](CONSISTENCY.md) when you want to know what
   changes at scale.
5. Open [`docs/flow.html`](docs/flow.html) for the visual explainer.

## Explicitly out of scope

Payments, PSPs, PCI-DSS, full order state machines, search/ranking, auth.
Each of those deserves its own repo. These POCs concentrate on the
mechanics that go wrong under load and cost real money.
