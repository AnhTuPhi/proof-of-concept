#!/usr/bin/env bash
# Exercises the orchestration (Temporal) saga end-to-end.
# Requires orchestrator-service (8090) running and Temporal at localhost:7233.

set -euo pipefail

ORDER_API="http://localhost:8090/orders"

post_order() {
  local customer="$1" product="$2" qty="$3" price="$4" addr="$5"
  curl -s -X POST "$ORDER_API" \
       -H 'Content-Type: application/json' \
       -d "{\"customerId\":\"$customer\",\"productId\":\"$product\",\"quantity\":$qty,\"unitPrice\":$price,\"shippingAddress\":\"$addr\"}"
}

get_order() {
  curl -s "$ORDER_API/$1"
}

wait_for_terminal() {
  local order_id="$1"
  for _ in $(seq 1 30); do
    local response status
    response=$(get_order "$order_id")
    status=$(echo "$response" | grep -o '"orderStatus":"[A-Z_]*"' | cut -d'"' -f4)
    case "$status" in
      COMPLETED|CANCELLED|FAILED) echo "$response"; return 0 ;;
    esac
    sleep 1
  done
  echo "Timeout waiting for $order_id"
  get_order "$order_id"
  return 1
}

echo "=========================================="
echo " Scenario 1: happy path"
echo "=========================================="
ok=$(post_order "cust-001" "SKU-1" 2 19.99 "123 Main St")
echo "$ok"
ok_id=$(echo "$ok" | grep -o '"orderId":"[a-z0-9-]*"' | cut -d'"' -f4)
wait_for_terminal "$ok_id"

echo
echo "=========================================="
echo " Scenario 2: payment fails (customer prefix 'deadbeat')"
echo "=========================================="
pay_fail=$(post_order "deadbeat-007" "SKU-2" 1 50.00 "456 Oak Ave")
echo "$pay_fail"
pay_fail_id=$(echo "$pay_fail" | grep -o '"orderId":"[a-z0-9-]*"' | cut -d'"' -f4)
wait_for_terminal "$pay_fail_id"

echo
echo "=========================================="
echo " Scenario 3: inventory fails (product prefix 'OUT_OF_STOCK')"
echo "=========================================="
inv_fail=$(post_order "cust-002" "OUT_OF_STOCK-X" 1 30.00 "789 Pine Rd")
echo "$inv_fail"
inv_fail_id=$(echo "$inv_fail" | grep -o '"orderId":"[a-z0-9-]*"' | cut -d'"' -f4)
wait_for_terminal "$inv_fail_id"

echo
echo "=========================================="
echo " Scenario 4: shipping fails (address contains 'INVALID')"
echo "=========================================="
ship_fail=$(post_order "cust-003" "SKU-3" 1 25.00 "INVALID address")
echo "$ship_fail"
ship_fail_id=$(echo "$ship_fail" | grep -o '"orderId":"[a-z0-9-]*"' | cut -d'"' -f4)
wait_for_terminal "$ship_fail_id"

echo
echo "All orchestration scenarios complete."
echo "Open the Temporal UI at http://localhost:8233 to inspect workflow histories."
