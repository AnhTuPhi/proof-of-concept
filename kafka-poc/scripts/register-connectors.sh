#!/usr/bin/env bash
set -euo pipefail

CONNECT_URL=${CONNECT_URL:-http://localhost:8083}

post() {
  local file=$1
  local name
  name=$(jq -r .name "$file")
  echo "Registering $name from $file"
  curl -fsS -X PUT \
       -H "Content-Type: application/json" \
       --data "$(jq .config "$file")" \
       "$CONNECT_URL/connectors/$name/config" | jq .
}

post 12-cdc-pipeline/connectors/01-debezium-oracle-source.json
post 12-cdc-pipeline/connectors/02-elasticsearch-sink.json

echo
echo "Connector status:"
curl -fsS "$CONNECT_URL/connectors?expand=status" | jq .
