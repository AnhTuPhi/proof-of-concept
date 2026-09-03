#!/usr/bin/env bash
# Demo 4 — Blue-Green flip + Canary ramp.

set -euo pipefail
BASE=${BASE:-http://localhost:8080}

users=(alice bob carol dave erin frank gina hank ivan judy kim leo mia nick olga peter quinn rita sam tara ulla vic xena yves zac)

fire() {
  curl -s -X POST "$BASE/router/reset-counters" >/dev/null
  for u in "${users[@]}"; do
    curl -s "$BASE/api/hello?userId=$u" >/dev/null
  done
  curl -s "$BASE/router/config" | jq -c '{mode, activeColor, canaryWeight, hits}'
}

echo "== BLUE-GREEN, active=BLUE: all traffic should go to BLUE"
curl -s -X POST "$BASE/router/mode?mode=BLUE_GREEN" >/dev/null
curl -s -X POST "$BASE/router/active-color?color=BLUE" >/dev/null
fire

echo
echo "== flip active=GREEN: all traffic instantly cuts over"
curl -s -X POST "$BASE/router/active-color?color=GREEN" >/dev/null
fire

echo
echo "== switch to CANARY, walk weight 0 -> 100"
curl -s -X POST "$BASE/router/mode?mode=CANARY" >/dev/null
for w in 0 5 10 25 50 75 100; do
  curl -s -X POST "$BASE/router/canary-weight?weight=$w" >/dev/null
  fire
done
