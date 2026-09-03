# ISSUE — Why these POCs exist

## The business problem

We are building recurring, money-moving billing for a SaaS product. Recurring billing
looks trivial ("charge the card every month") until it meets reality:

- Customers **change plans in the middle of a billing period** and expect a fair,
  to-the-day settlement — without us handing back cash.
- **Cards fail.** Between 5% and 15% of recurring card charges are declined on the
  first attempt (expiry, insufficient funds, issuer fraud rules). If we cancel on the
  first failure we throw away recoverable revenue; if we never give up we look predatory
  and rack up processor fees.
- **Usage is metered.** Meters emit millions of events that arrive **duplicated, out of
  order, and late**. We must turn that firehose into an invoice the customer can audit to
  the cent.

Every one of these paths moves money. A bug does not print a wrong pixel — it
**overcharges a customer, drops revenue, or issues a refund that should never have left
the building.** Those are the failures that produce chargebacks, support escalations,
and finance-team reconciliation nightmares.

## The core invariants we are protecting

These hold across all three POCs. Everything in the code exists to protect one of them:

| # | Invariant | What breaks if we lose it |
|---|-----------|---------------------------|
| I1 | **Every amount is reproducible and auditable.** Given the same inputs, the same cents come out, and each line item explains itself. | Disputes we cannot defend; finance cannot close the books. |
| I2 | **No double-charge.** A retried renewal or a replayed usage event never bills twice. | Chargebacks, refunds, churn. |
| I3 | **No dropped revenue.** A late or duplicated event is still counted exactly once; a recoverable failed payment is retried. | Silent under-billing; unrecoverable MRR. |
| I4 | **Money never leaves incorrectly.** A downgrade produces *credit*, not a cash refund. | Fraud surface, accounting drift, processor refund fees. |
| I5 | **State transitions are legal and idempotent.** A subscription only moves along allowed edges, and re-running a batch job changes nothing the second time. | Corrupted lifecycle state; charges against canceled subs. |

## Why three separate POCs

Each POC isolates **one genuinely hard sub-problem** so it can be reasoned about, tested,
and read on its own. They deliberately share only the `common` money primitives:

| POC | The hard problem it isolates | Primary invariants |
|-----|------------------------------|--------------------|
| `proration-engine-poc` | Fair, no-refund settlement of a mid-cycle plan change, to the day, without rounding drift. | I1, I4 |
| `dunning-poc` | Deterministic, humane, idempotent recovery of failed payments through a lifecycle state machine. | I2, I3, I5 |
| `usage-billing-poc` | Idempotent ingestion of an unreliable event stream → rebuildable aggregates → tiered invoice the customer can reconcile. | I1, I2, I3 |

## Explicitly out of scope

These POCs prove the *hard algorithmic and correctness cores*. They are **not** a
production billing platform. The following are intentionally faked or omitted, and are
tracked as tech debt in [TECHNICAL.md](TECHNICAL.md):

- Real payment-processor integration (charging is a deterministic fake gateway).
- A real scheduler — time is advanced by an API parameter (`today` / `asOf`) so scenarios
  can be replayed without touching the wall clock.
- A shared database — each module runs an in-memory H2, which is *per-instance* and does
  not survive horizontal scaling. See [CONSISTENCY.md](CONSISTENCY.md) for what changes
  when this runs as N pods or VMs.
- Auth, multi-currency settlement, tax, and dunning of usage invoices.

## Where to read next

- **[TECHNICAL.md](TECHNICAL.md)** — per-POC deep dive: solution shape, key tech by
  responsibility, how each sub-problem is solved, and acknowledged tech debt.
- **[CONSISTENCY.md](CONSISTENCY.md)** — what must change to run this correctly behind a
  Kubernetes Deployment (N pods) or a fleet of VMs.
- **[docs/overview.html](docs/overview.html)** — an interactive, self-contained visual
  walkthrough of the three flows and the consistency model.
