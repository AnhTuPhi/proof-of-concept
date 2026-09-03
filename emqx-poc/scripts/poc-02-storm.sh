#!/usr/bin/env bash
# Reconnect storm with the four strategies.
#   STRATEGY=DECORRELATED_JITTER COUNT=5000 ./scripts/poc-02-storm.sh
set -euo pipefail
COUNT=${COUNT:-2000}
STRATEGY=${STRATEGY:-DECORRELATED_JITTER}

echo "starting storm: $COUNT clients, $STRATEGY"
curl -s -X POST "localhost:8102/storm/start?count=$COUNT&strategy=$STRATEGY"
echo
echo "watch CPU/conn rate on:"
echo "  curl -s localhost:8102/storm/metrics | jq"
