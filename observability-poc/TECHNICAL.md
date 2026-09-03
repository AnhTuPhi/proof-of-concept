# TECHNICAL.md — how this POC solves the hard problems

Read [ISSUE.md](ISSUE.md) first for *why*. This document is the *how*: the hard
problem behind each sub-POC, what we are protecting, the shape of the solution, the
key technology chosen for each responsibility, and the tech debt we are knowingly
carrying.

---

## The system at a glance

```
                    context propagated via W3C traceparent header
        ┌────────────────────────────────────────────────────────────┐
        │                                                              ▼
client ─┴─► order-service ──► payment-service            inventory-service
             (fan-out)    └─► inventory-service                 │
                │                                               │
    every service emits 3 signals over ONE wire format (OTLP)   │
                ▼                                                ▼
        ┌───────────────────────────────────────────────────────────┐
        │              OpenTelemetry Collector                        │
        │   receive (OTLP) → memory_limiter → resource → batch        │
        │            └── fan out per signal type ──┐                  │
        └──────┬─────────────────┬─────────────────┬──────────────────┘
               │ traces          │ metrics         │ logs
               ▼                 ▼                 ▼
            Jaeger          Prometheus            Loki
               └────────────────┼─────────────────┘
                                ▼
                             Grafana  (single pane; cross-links via trace_id)
```

The whole design rests on **one idea**: give every request a `trace_id`, stamp that
id onto *all three* signal types, and ship everything over *one* vendor-neutral
protocol. Everything below is a consequence of that.

---

## Sub-POC 1 — Distributed tracing (causal timeline across services)

**The hard problem.** `order-service` calls payment and inventory *in parallel*
([order-service/src/index.ts:50](services/order-service/src/index.ts)). From the edge
you see one latency number. You cannot tell which downstream hop was slow, or whether
a failure originated in payment vs. inventory, because each process only knows about
itself. Timestamps don't line up reliably once requests overlap.

**What we're protecting.** *Mean-time-to-diagnosis.* The ability to point at the exact
hop and span that failed or stalled, for one specific request, without guessing.

**Solution shape.** A **trace** is a tree of **spans**. A root span is created at the
edge; every outbound HTTP call injects a **W3C `traceparent` header**; the receiving
service extracts it and makes its spans children of the caller's span. The tree
reassembles server-side from spans that arrived independently.

**Key tech by responsibility.**

| Responsibility | Tech | Where |
|---|---|---|
| Create/propagate spans with no per-route code | `@opentelemetry/auto-instrumentations-node` (http + express) | [telemetry.ts:37](services/order-service/src/telemetry.ts) |
| Business-meaningful spans + attributes | Manual `tracer.startActiveSpan('order.process', …)` | [index.ts:45](services/order-service/src/index.ts) |
| Cross-process context | W3C Trace Context header (OTel default propagator) | injected by the http instrumentation |
| Store & visualise the tree | Jaeger all-in-one | `docker-compose.yml` |

**How it solves the sub-problem.** The parallel `Promise.all` fan-out produces two
sibling child spans under `order.process`; in Jaeger their bars sit side by side, so
"payment was slow" vs. "inventory stock_lookup was slow" is *visible*, not inferred.
The nested `inventory.stock_lookup` child span
([inventory-service/src/index.ts:49](services/inventory-service/src/index.ts)) shows
the occasional 200–500 ms DB stall as a distinct bar. Failed payments set
`SpanStatusCode.ERROR` and `recordException`, so error traces are filterable.

---

## Sub-POC 2 — Metrics (is something wrong, and how often?)

**The hard problem.** Traces are per-request and (in production) sampled — they can't
tell you "what % of charges failed in the last hour." You need cheap, always-on,
aggregatable numbers, but you also need to *not* lose the bridge back to an example
request.

**What we're protecting.** *Signal for alerting and trend detection* — the "wake
someone up" layer — without paying to store every request.

**Solution shape.** Application-defined **counters** and **histograms**, exported on a
fixed interval, scraped by Prometheus, with **exemplars** linking a data point back to
a representative `trace_id`.

**Key tech by responsibility.**

| Responsibility | Tech | Where |
|---|---|---|
| Define instruments | OTel Metrics API (`meter.createCounter/Histogram`) | [order](services/order-service/src/index.ts:14), [payment](services/payment-service/src/index.ts:11) |
| Periodic push to collector | `PeriodicExportingMetricReader` (10 s) | [telemetry.ts:30](services/order-service/src/telemetry.ts) |
| Expose for scraping | Collector `prometheus` exporter on `:8889` | [otel-collector/config.yaml:31](otel-collector/config.yaml) |
| Pull & store time-series | Prometheus (2 h retention in POC) | `docker-compose.yml` |
| Metric → trace jump | Grafana `exemplarTraceIdDestinations` | [datasources.yaml:12](grafana/provisioning/datasources/datasources.yaml) |

**How it solves the sub-problem.** `orders_created_total{status}`,
`payment_charges_total{outcome}`, and `payment_charge_duration_ms` (histogram) give
rate, error-ratio, and latency-distribution dashboards. Because metrics flow through
the *same collector* as traces, Prometheus can carry exemplars, so a spike on the
error graph is one click from a real failing trace.

---

## Sub-POC 3 — Structured logs, correlated to traces

**The hard problem.** Logs have the richest detail ("gateway declined") but are the
worst to correlate: free-text, per-process, no shared key. Grepping three log files
by timestamp collapses the instant two requests overlap.

**What we're protecting.** *The detailed narrative of one request* — and the ability to
reach it from a trace and vice-versa.

**Solution shape.** JSON logs (not free text) that are **automatically enriched** with
the active `trace_id`/`span_id`, shipped as OTLP log records, indexed by labels only
(not full-text) for cheap storage, and cross-linked in Grafana by regex on `trace_id`.

**Key tech by responsibility.**

| Responsibility | Tech | Where |
|---|---|---|
| Structured JSON logging | `pino` | [logger.ts](services/order-service/src/logger.ts) |
| Inject trace_id/span_id into every line | `@opentelemetry/instrumentation-pino` | [telemetry.ts:40](services/order-service/src/telemetry.ts) |
| Ship logs as OTLP | `BatchLogRecordProcessor` + OTLP log exporter | [telemetry.ts:34](services/order-service/src/telemetry.ts) |
| Store, label-index | Loki 3.x (native OTLP endpoint `/otlp`) | [otel-collector/config.yaml:37](otel-collector/config.yaml) |
| Log line → trace jump | Grafana Loki `derivedFields` regex on `"trace_id"` | [datasources.yaml:22](grafana/provisioning/datasources/datasources.yaml) |

**How it solves the sub-problem.** Because pino runs *inside* an active span, each line
carries `trace_id`. Grafana's derived field turns that id into a clickable link to
Jaeger; Jaeger's `tracesToLogsV2` does the reverse. The three silos become one graph:
**metric spike → exemplar trace → log lines → back to trace.**

---

## Sub-POC 4 — The Collector as a decoupling seam

**The hard problem.** The obvious way to get telemetry out is to make each service talk
directly to Jaeger + Prometheus + Loki. That hard-wires every service to three
backends' addresses, auth, and wire formats. Change a backend → redeploy every service.
Also, apps doing their own ret/batch/backpressure is fragile.

**What we're protecting.** *Freedom to change the backend without touching application
code*, and *the apps from telemetry-side backpressure.*

**Solution shape.** Services know exactly **one** endpoint —
`OTEL_EXPORTER_OTLP_ENDPOINT` — and one protocol (OTLP). The **Collector** owns all
knowledge of the real backends and does the fan-out per signal type.

**Key tech by responsibility.**

| Responsibility | Tech | Where |
|---|---|---|
| One wire format in | OTLP receiver (gRPC 4317 / HTTP 4318) | [config.yaml:1](otel-collector/config.yaml) |
| Protect collector RAM | `memory_limiter` processor | [config.yaml:13](otel-collector/config.yaml) |
| Amortise network | `batch` processor | [config.yaml:10](otel-collector/config.yaml) |
| Tag origin | `resource` processor | [config.yaml:17](otel-collector/config.yaml) |
| Fan out per signal | 3 exporters (Jaeger / Prometheus / Loki) | [config.yaml:23](otel-collector/config.yaml) |

**How it solves the sub-problem.** Swapping Jaeger for Tempo, or Loki for a SaaS, is a
change to *one file* (the collector config) with **zero application redeploys**.
Pointing `OTEL_EXPORTER_OTLP_ENDPOINT` at Honeycomb/Datadog/Grafana Cloud is one env
var. The apps are insulated: the collector absorbs batching, retries, and memory
pressure so a slow backend degrades telemetry, not the request path.

---

## Cross-cutting: resource attributes as the join key

Every signal from every service carries the same **resource attributes** —
`service.name`, `service.version`, `deployment.environment`
([telemetry.ts:21](services/order-service/src/telemetry.ts)). This is what lets
Grafana pivot "this trace" → "logs for this service" → "metrics for this service"
using shared tags ([datasources.yaml:34](grafana/provisioning/datasources/datasources.yaml)).
Without consistent resource attributes, the single-pane story falls apart.

---

## Tech debt to acknowledge

These are deliberate POC shortcuts. Do **not** ship them as-is.

1. **No sampling — every trace is kept.** Fine at 50 req/burst; ruinous at production
   volume. Add **tail-based sampling** in the collector (keep all errors + slow traces,
   sample the rest). Requires a *stateful* collector tier — see
   [CONSISTENCY.md](CONSISTENCY.md).

2. **Single, stateless collector = single point of failure.** One container, no HA.
   Production wants a load-balanced *agent → gateway* collector topology.

3. **All storage is ephemeral / tiny.** Prometheus retention 2 h, Loki 24 h, Loki on
   local `filesystem` with `replication_factor: 1` and in-memory ring. Data is lost on
   restart. Real deployments need object storage (S3/GCS) and real retention.

4. **No auth / TLS anywhere.** `auth_enabled: false` in Loki, `tls insecure: true` to
   Jaeger, anonymous Grafana viewer, `admin/admin`. Everything is plaintext on a
   trusted Docker network. Unacceptable outside a laptop.

5. **No cardinality guarding.** `inventory_items_reserved_total{sku}` and per-`sku`
   histograms are safe here because SKUs are few and fixed. A high-cardinality label
   (e.g. `order_id`, `user_id`) would explode Prometheus. Needs a policy/linter.

6. **Metrics-via-Prometheus-scrape loses data on collector restart / scale event.** The
   pull model + a stateless collector means counters reset when the pod cycles. See the
   scaling discussion in [CONSISTENCY.md](CONSISTENCY.md).

7. **`resource_to_telemetry_conversion: true`** promotes *all* resource attributes to
   Prometheus labels — convenient, but another cardinality footgun at scale.

8. **Failure/latency is simulated** (`Math.random() < 0.1`, `sleep(...)`). There is no
   real database, gateway, or persistence — the POC demonstrates the *observability*
   plumbing, not the business logic.

9. **`depends_on` is not readiness.** Compose start-order ≠ "the collector is accepting
   OTLP." Early telemetry on cold start can be dropped. Real orchestration needs
   readiness probes (again, [CONSISTENCY.md](CONSISTENCY.md)).
