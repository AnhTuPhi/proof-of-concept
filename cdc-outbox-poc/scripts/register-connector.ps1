# Registers the Debezium outbox connector. Idempotent.
$ErrorActionPreference = "Stop"

$ConnectUrl    = if ($env:CONNECT_URL) { $env:CONNECT_URL } else { "http://localhost:8083" }
$ConfigFile    = Join-Path $PSScriptRoot "..\debezium-config\outbox-connector.json"
$ConnectorName = "order-outbox-connector"

Write-Host "Waiting for Debezium Connect to be ready at $ConnectUrl ..."
$ready = $false
for ($i = 1; $i -le 30; $i++) {
    try {
        Invoke-RestMethod -Uri "$ConnectUrl/connectors" -Method Get -TimeoutSec 5 | Out-Null
        $ready = $true
        Write-Host "Connect is up."
        break
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $ready) {
    Write-Error "Connect did not become ready in time."
    exit 1
}

# Best-effort delete
Write-Host "Removing existing connector if present ..."
try {
    Invoke-RestMethod -Uri "$ConnectUrl/connectors/$ConnectorName" -Method Delete -TimeoutSec 10 | Out-Null
} catch {
    # ignore if not present
}

Write-Host "Registering connector from $ConfigFile ..."
$body = Get-Content -Raw -Path $ConfigFile
$result = Invoke-RestMethod -Uri "$ConnectUrl/connectors" -Method Post `
    -Headers @{ "Content-Type" = "application/json" } -Body $body
$result | ConvertTo-Json -Depth 10

Write-Host ""
Write-Host "Connector status:"
$status = Invoke-RestMethod -Uri "$ConnectUrl/connectors/$ConnectorName/status" -Method Get
$status | ConvertTo-Json -Depth 10
