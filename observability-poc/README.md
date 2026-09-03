# observability-poc

End-to-end **OpenTelemetry** demo: distributed traces, metrics, structured logs, and correlation IDs flowing across three Node.js microservices into a self-hosted observability stack.

> **Why this exists.** OTel is the de-facto standard at Netflix, Uber, Lyft. This repo is a 5-minute, batteries-included POC that shows the *three pillars* (traces, metrics, logs) and how they correlate — without standing up a SaaS account.

## Documentation

| Doc | Answers |
|---|---|
| [ISSUE.md](ISSUE.md) | **Why** — the problem this POC exists to solve (a failed/slow request nobody can trace across services). |
| [TECHNICAL.md](TECHNICAL.md) | **How** — the hard problem behind each sub-POC, what we protect, solution shape, key tech by responsibility, and acknowledged tech debt. |
| [CONSISTENCY.md](CONSISTENCY.md) | **At scale** — what breaks (and what's free) when you run many replicas via K8s pods or VMs, and how to fix it. |
| [demo.html](demo.html) | **Visual** — self-contained, interactive walkthrough of the flow and key tech. Open it in any browser. |

### The core idea in one paragraph

Give every request a `trace_id`, stamp that id onto **all three** signal types (traces,
metrics, logs), and ship everything over **one** vendor-neutral protocol (OTLP) to a
single **Collector** that fans out to the backends. That is what makes one ID enough to
follow a request across every service — and what lets you swap any backend by editing
one config file, with zero application changes.

## Architecture

```
            HTTP                          HTTP
client ──────────► order-service ──────────► payment-service
                       │
                       └──────────────────► inventory-service
                                                  │
       all three services emit OTLP (gRPC / HTTP) │
                       ▼                          ▼
                ┌───────────────────────────────────────┐
                │       OpenTelemetry Collector         │
                │  (receives → batches → fans out)      │
                └────┬───────────────┬───────────────┬──┘
                     │ traces        │ metrics       │ logs
                     ▼               ▼               ▼
                  Jaeger         Prometheus         Loki
                     └───────────────┼───────────────┘
                                     ▼
                                  Grafana
                          (single pane of glass)
```

- **Traces** — Jaeger UI at <http://localhost:16686>
- **Metrics** — Prometheus at <http://localhost:9090>
- **Logs** — Loki, queried through Grafana
- **Unified** — Grafana at <http://localhost:3000> (`admin` / `admin`)
- **OTel Collector** — internal, exposes OTLP receivers on `:4317` (gRPC) and `:4318` (HTTP)

### Three pillars, correlated

Every log line emitted by a service carries the active `trace_id` and `span_id`. In Grafana, clicking a trace span jumps to the matching log lines (and vice-versa) via **trace-to-logs** and **logs-to-trace** datasource links.

## Quick start

```bash
# 1. Build & start everything
docker compose up --build -d

# 2. Generate some traffic (Linux / macOS / WSL)
./scripts/load.sh

#    or, on Windows PowerShell:
./scripts/load.ps1

# 3. Open Grafana
#    http://localhost:3000  (admin / admin)
#    The "Observability POC" dashboard is auto-provisioned.

# 4. Open Jaeger to inspect a single trace
#    http://localhost:16686  → service: order-service → Find Traces
```

To stop everything:

```bash
docker compose down -v
```

## Services

| Service | Port | Endpoint | What it does |
|---|---|---|---|
| `order-service` | 3001 | `POST /orders` | Orchestrates: calls payment + inventory in parallel |
| `payment-service` | 3002 | `POST /charge` | Simulates charging a card (~10% failure rate) |
| `inventory-service` | 3003 | `POST /reserve` | Simulates reserving stock (variable latency) |

Each service exposes `GET /health` for the dockerised healthchecks.

### Try a single request

```bash
curl -X POST http://localhost:3001/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-1","qty":2,"amount":4999}'
```

Sample response:

```json
{
  "orderId": "ord_8f3c…",
  "status": "confirmed",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

The `trace_id` is the same one you'll find in Jaeger and in the structured log lines for this request.

## What's instrumented

- **Auto-instrumentation** via `@opentelemetry/auto-instrumentations-node` — picks up `http`, `express`, and `pino` with zero code per route.
- **Manual spans** in business logic where it adds value (e.g. `order.validate`, `payment.charge`).
- **Custom metrics**:
  - `orders_created_total{status}` (counter)
  - `payment_charge_duration_ms` (histogram)
  - `inventory_items_reserved_total{sku}` (counter)
- **Structured logs** via [`pino`](https://github.com/pinojs/pino), auto-enriched with `trace_id` / `span_id` / `trace_flags` by `@opentelemetry/instrumentation-pino`.
- **Resource attributes** — `service.name`, `service.version`, `deployment.environment` set on every signal.

## Layout

```
.
├── docker-compose.yml          # 8 containers wired together
├── services/
│   ├── order-service/          # Node.js + TypeScript
│   ├── payment-service/
│   └── inventory-service/
├── otel-collector/config.yaml  # OTLP in → Jaeger / Prom / Loki out
├── prometheus/prometheus.yml   # scrapes the collector
├── loki/loki-config.yaml
├── grafana/
│   ├── provisioning/           # auto-loaded datasources + dashboard
│   └── dashboards/
└── scripts/load.{sh,ps1}       # generates traffic
```

## Extending this

- Swap any service for Python / Go / Java — the **collector contract is OTLP**, so the rest of the stack stays identical.
- Add tail-based sampling in the collector to keep only error / slow traces in production.
- Point `OTEL_EXPORTER_OTLP_ENDPOINT` at a SaaS backend (Honeycomb, Datadog, Grafana Cloud, …) instead of the local collector — same code.

## License

MIT — see [LICENSE](LICENSE).
