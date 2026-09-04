#!/usr/bin/env bash
# Starts all four choreography microservices in parallel background jobs.
# Each writes its log to logs/<service>.log. Press Ctrl-C to stop them all.

set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p logs
SERVICES=(order-service payment-service inventory-service shipping-service)

trap 'echo; echo "Stopping..."; jobs -p | xargs -r kill 2>/dev/null; wait 2>/dev/null; exit 0' INT TERM

for svc in "${SERVICES[@]}"; do
  echo "Starting $svc (logs/$svc.log)"
  ( cd "choreography/$svc" && mvn -q spring-boot:run ) >"logs/$svc.log" 2>&1 &
done

echo
echo "Started:"
echo "  order-service       http://localhost:8081/swagger-ui.html"
echo "  payment-service     http://localhost:8082"
echo "  inventory-service   http://localhost:8083"
echo "  shipping-service    http://localhost:8084"
echo
echo "Tail a log with:  tail -f logs/order-service.log"
echo "Press Ctrl-C to stop all four services."
wait
