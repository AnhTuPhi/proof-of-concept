#!/usr/bin/env bash
# Generate sample traffic against the order-service.
# Mixes happy-path requests, an out-of-stock SKU, and an occasional bad payload.

set -euo pipefail
URL="${ORDER_URL:-http://localhost:3001/orders}"
ITERATIONS="${ITERATIONS:-50}"

SKUS=(WIDGET-1 WIDGET-2 GADGET-7 OUT-OF-STOCK)

for i in $(seq 1 "$ITERATIONS"); do
  sku=${SKUS[$RANDOM % ${#SKUS[@]}]}
  qty=$((RANDOM % 4 + 1))
  amount=$((RANDOM % 9000 + 100))

  # ~5% of requests are intentionally malformed to surface 4xx traces
  if (( RANDOM % 20 == 0 )); then
    body='{"sku":"WIDGET-1"}'
  else
    body=$(printf '{"sku":"%s","qty":%d,"amount":%d}' "$sku" "$qty" "$amount")
  fi

  echo "[$i/$ITERATIONS] $body"
  curl -sS -o /dev/null -w "  → %{http_code} in %{time_total}s\n" \
    -X POST "$URL" \
    -H 'Content-Type: application/json' \
    -d "$body" || true

  sleep 0.2
done
