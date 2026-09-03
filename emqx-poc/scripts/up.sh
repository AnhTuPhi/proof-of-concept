#!/usr/bin/env bash
# Bring the cluster up and wait for it to settle.
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose up -d emqx1 emqx2 emqx3 haproxy postgres kafka prometheus grafana

echo "waiting for emqx cluster to form..."
for i in {1..60}; do
  if docker exec emqx1 emqx_ctl cluster status 2>/dev/null | grep -q 'running_nodes.*emqx2.*emqx3'; then
    echo "cluster up"
    docker exec emqx1 emqx_ctl cluster status
    exit 0
  fi
  sleep 2
done
echo "ERROR: cluster did not form within 120s"
exit 1
