#!/usr/bin/env bash
# Demo 2 — graceful shutdown with request draining.
#
# In a SECOND terminal, run the app with:
#   WARMUP_MS=0 ./gradlew bootRun
#
# Then in this terminal:
#   ./scripts/02-graceful-shutdown.sh
#
# What happens:
#   1. We fire 5 parallel /work requests, each takes 8s.
#   2. We send SIGTERM to the app while requests are mid-flight.
#   3. App flips readiness=REFUSING_TRAFFIC immediately.
#   4. App waits drain-grace-ms (2s), then waits for in-flight=0.
#   5. All 5 requests complete normally; readiness shows 503 throughout drain.

set -euo pipefail
BASE=${BASE:-http://localhost:8080}

PID=$(lsof -ti:8080 || true)
if [[ -z "$PID" ]]; then
  echo "No process on :8080 — start the app first (./gradlew bootRun)"
  exit 1
fi
echo "App PID = $PID"

echo "== firing 5 parallel /work?ms=8000 requests"
for i in 1 2 3 4 5; do
  (curl -s "$BASE/work?ms=8000" | jq -c ". + {req: $i}") &
done

sleep 1
echo "== readiness BEFORE SIGTERM:"
curl -s -o /dev/null -w "  HTTP %{http_code}\n" "$BASE/actuator/health/readiness"

echo "== sending SIGTERM"
kill -TERM "$PID"

sleep 1
echo "== readiness 1s AFTER SIGTERM (should be 503, drain in progress):"
curl -s -w "  HTTP %{http_code}\n" "$BASE/actuator/health/readiness" | head -c 500; echo

echo "  (during the drain-grace window the server intentionally keeps listening"
echo "   — the LB needs time to observe readiness=503 and re-route. Only AFTER"
echo "   that does Tomcat refuse new connections.)"

echo "== waiting for in-flight requests to finish..."
wait
echo "== all parallel /work calls returned. drain successful."
