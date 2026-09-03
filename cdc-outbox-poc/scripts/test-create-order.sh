#!/usr/bin/env bash
# Smoke test: create an order via order-service, then verify the outbox row,
# Kafka message, and notification-service consumption.

set -euo pipefail

ORDER_SERVICE="${ORDER_SERVICE:-http://localhost:8080}"

echo "1) Creating order ..."
RESPONSE=$(curl -fsS -X POST "${ORDER_SERVICE}/api/orders" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-001",
    "productSku": "SKU-42",
    "quantity": 2,
    "unitPrice": 19.99
  }')

echo "Response:"
echo "${RESPONSE}" | sed 's/.*/  &/'
echo

ORDER_ID=$(echo "${RESPONSE}" | grep -oE '"id"[^,]*' | head -1 | sed -E 's/.*"([0-9a-f-]+)"/\1/')
echo "Order id: ${ORDER_ID}"
echo

echo "2) Verifying outbox row in Postgres ..."
docker compose exec -T postgres psql -U cdc -d cdc -c \
  "SELECT id, aggregate_type, aggregate_id, event_type, created_at FROM outbox_events ORDER BY created_at DESC LIMIT 5;"

echo
echo "3) Check Kafka UI at http://localhost:8090 (topic: outbox.event.Order)"
echo "4) Tail notification-service logs:"
echo "   docker compose logs --tail=20 notification-service"
