#!/usr/bin/env bash
# Cause a deliberate split-brain and observe.
set -euo pipefail
NET=${NET:-emqx-production-patterns-poc_default}
NODE=${NODE:-emqx3}

echo "=== before split ==="
curl -s localhost:8114/cluster/probe | jq

echo "disconnecting $NODE from $NET"
docker network disconnect "$NET" "$NODE"

echo "waiting 15s for netsplit detection..."
sleep 15

echo "=== split-brain state ==="
curl -s localhost:8114/cluster/probe | jq
echo
echo "membership from each surviving Mgmt API:"
curl -s localhost:8114/cluster/membership | jq

echo
read -p "press enter to heal..."
docker network connect "$NET" "$NODE"
sleep 15

echo "=== after heal ==="
curl -s localhost:8114/cluster/probe | jq
