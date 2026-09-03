# ISSUE — Why this POC exists

## The problem in one sentence

> When a request fails or gets slow in a system of many small services, **nobody can
> answer "what actually happened to *this* request?"** — because the evidence is
> scattered across machines, formats, and tools that don't talk to each other.

## The situation we're modelling

A user clicks **"Place order."** Behind that single click:

```
client → order-service → payment-service   (charge the card)
                       → inventory-service  (reserve the stock)
```

Three processes, potentially on three different machines, each with its own logs,
its own CPU/latency behaviour, its own failure modes. The `order-service` fans out
to `payment` and `inventory` *in parallel* and only succeeds if both do.

## What goes wrong today (the pain)

| Symptom the user reports | What the on-call engineer actually has to work with |
|---|---|
| "My order failed." | Three separate log files. No shared key to line them up. |
| "It was slow." | A latency number on the edge, but *no idea which hop* ate the time. |
| "It fails sometimes." | ~10% of payments fail randomly — invisible in aggregate until it's a fire. |
| "Is it just me?" | No way to tell one bad request apart from a system-wide outage. |

The root causes, stated plainly:

1. **No shared identity for a request.** `order-service` logs `order failed`,
   `payment-service` logs `gateway declined`, but nothing proves those two lines
   belong to the *same* user action. Correlation is done by eyeball and timestamp —
   which breaks the moment two requests overlap.

2. **No causal timeline across process boundaries.** Each service can time *itself*,
   but the parallel fan-out to payment + inventory means you can't tell, from the
   edge, whether the slow one was the card charge or the stock lookup.

3. **Three signals, three silos.** Metrics answer *"is something wrong?"*, logs answer
   *"what was the error?"*, traces answer *"where in the path?"* — but if they live in
   three disconnected tools you spend the outage **copy-pasting IDs between browser
   tabs** instead of fixing the bug.

4. **Vendor / language lock-in risk.** The naïve fix — bolt a SaaS agent onto every
   service — couples every service to one vendor's SDK and one wire format. Swapping
   the backend later means re-instrumenting everything.

## What "solved" looks like

For **any** request, an engineer should be able to:

- take **one ID** (`trace_id`) and see the *entire* path it took across all services;
- jump from a **metric spike** → an **exemplar trace** → the exact **log lines** that
  request produced, without ever leaving one screen;
- see that a failure was **that request only** vs. a system-wide pattern;
- do all of the above **without changing application code** if the backend changes.

That is the target this POC demonstrates end-to-end. The *how* — the tech choices and
the trade-offs behind them — is in [TECHNICAL.md](TECHNICAL.md). How it holds up when
you run many copies of each service is in [CONSISTENCY.md](CONSISTENCY.md).
