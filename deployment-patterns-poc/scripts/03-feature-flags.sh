#!/usr/bin/env bash
# Demo 3 — feature flags: deploy != release.
#
# `new-checkout` ships in v2 but starts at 0% rollout. We progressively
# raise rollout and watch the same user-pool flip implementations without
# any redeploy.

set -euo pipefail
BASE=${BASE:-http://localhost:8080}

users=(alice bob carol dave erin frank gina hank ivan judy kim leo mia nick olga peter)

count_v2() {
  local on=0
  for u in "${users[@]}"; do
    impl=$(curl -s "$BASE/checkout?userId=$u" | jq -r .implementation)
    if [[ "$impl" == v2* ]]; then on=$((on+1)); fi
  done
  echo "$on / ${#users[@]} users on v2-new-checkout"
}

set_flag() {
  curl -s -X POST "$BASE/flags/new-checkout?enabled=true&rolloutPercent=$1" | jq -c .
}

echo "== initial: rollout = 0%"
set_flag 0
count_v2

for pct in 10 25 50 100; do
  echo
  echo "== bump rollout -> ${pct}%"
  set_flag $pct
  count_v2
done

echo
echo "== flag killswitch off — everyone instantly back to v1"
curl -s -X POST "$BASE/flags/new-checkout?enabled=false&rolloutPercent=100" | jq -c .
count_v2
