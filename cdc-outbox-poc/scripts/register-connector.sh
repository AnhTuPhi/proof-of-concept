#!/usr/bin/env bash
# Registers the Debezium outbox connector. Idempotent: deletes any existing
# connector with the same name first.

set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONFIG_FILE="$(dirname "$0")/../debezium-config/outbox-connector.json"
CONNECTOR_NAME="order-outbox-connector"

echo "Waiting for Debezium Connect to be ready at ${CONNECT_URL} ..."
for i in {1..30}; do
  if curl -fsS "${CONNECT_URL}/connectors" >/dev/null 2>&1; then
    echo "Connect is up."
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "Connect did not become ready in time." >&2
    exit 1
  fi
  sleep 2
done

# Best-effort delete (ignore failure if the connector doesn't exist).
echo "Removing existing connector if present ..."
curl -fsS -X DELETE "${CONNECT_URL}/connectors/${CONNECTOR_NAME}" >/dev/null 2>&1 || true

echo "Registering connector from ${CONFIG_FILE} ..."
curl -fsS -X POST \
  -H "Content-Type: application/json" \
  --data "@${CONFIG_FILE}" \
  "${CONNECT_URL}/connectors" \
  | sed 's/.*/  &/'

echo
echo "Connector registered. Status:"
curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" | sed 's/.*/  &/'
echo
