#!/usr/bin/env bash
# Drive POC 01 - Million Connections to a chosen scale.
#   NUM_CONNS=200000 ./scripts/poc-01-million-conns.sh
set -euo pipefail
NUM=${NUM_CONNS:-50000}
RATE=${RAMP_RATE:-2000}
BROKER=${BROKER:-tcp://localhost:1880}

echo "starting fleet: $NUM conns at $RATE/s to $BROKER"
curl -s -X POST "localhost:8101/fleet/start?count=$NUM&ratePerSec=$RATE&brokerUrl=$BROKER"
echo
echo "watch progress:"
echo "  watch -n2 'curl -s localhost:8101/fleet/status | jq'"
echo "  EMQX dashboard: http://localhost:18083 (admin / public)"
