# Smoke test for order creation end-to-end.
$ErrorActionPreference = "Stop"

$OrderService = if ($env:ORDER_SERVICE) { $env:ORDER_SERVICE } else { "http://localhost:8080" }

Write-Host "1) Creating order ..."
$body = @{
    customerId = "cust-001"
    productSku = "SKU-42"
    quantity   = 2
    unitPrice  = 19.99
} | ConvertTo-Json

$response = Invoke-RestMethod -Uri "$OrderService/api/orders" -Method Post `
    -Headers @{ "Content-Type" = "application/json" } -Body $body

$response | ConvertTo-Json -Depth 10
Write-Host ""
Write-Host "Order id: $($response.id)"
Write-Host ""

Write-Host "2) Verifying outbox row in Postgres ..."
docker compose exec -T postgres psql -U cdc -d cdc -c `
    "SELECT id, aggregate_type, aggregate_id, event_type, created_at FROM outbox_events ORDER BY created_at DESC LIMIT 5;"

Write-Host ""
Write-Host "3) Check Kafka UI at http://localhost:8090 (topic: outbox.event.Order)"
Write-Host "4) Tail notification-service logs:"
Write-Host "   docker compose logs --tail=20 notification-service"
