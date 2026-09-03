#!/usr/bin/env bash
# Demo 1 — tiered health probes.
#
#   STARTUP probe: DOWN until warmup-ms elapses (3s by default).
#   LIVENESS:      UP unless the JVM is hung. Stays UP even if DB is down.
#   READINESS:     drops when DB/downstream is down -> LB pulls pod out.

set -euo pipefail
BASE=${BASE:-http://localhost:8080}

probe() {
  printf "  %-10s -> " "$1"
  curl -s -o /tmp/probe.json -w "HTTP %{http_code}\n" "$BASE/actuator/health/$1" || true
  jq -c '{status, components: (.components // {} | to_entries | map({(.key): .value.status}) | add)}' /tmp/probe.json 2>/dev/null || cat /tmp/probe.json
  echo
}

echo "== T+0s: startup probe should be DOWN (warming up)"
probe startup
probe liveness
probe readiness

echo "== sleeping past warmup (4s)..."
sleep 4

echo "== T+4s: all three should be UP"
probe startup
probe liveness
probe readiness

echo "== kill the DB — readiness drops, liveness stays UP"
curl -s -X POST "$BASE/admin/deps/db?up=false" | jq .
probe liveness
probe readiness

echo "== bring DB back"
curl -s -X POST "$BASE/admin/deps/db?up=true" | jq .
probe readiness
