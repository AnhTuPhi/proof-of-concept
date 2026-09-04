# db-to-es-sync-poc — canonical scenarios.
#
# Usage:
#   ./scripts/demo.ps1 happy-path
#   ./scripts/demo.ps1 dual-write-drift
#   ./scripts/demo.ps1 outbox-recovery
#   ./scripts/demo.ps1 cdc-flow

param([Parameter(Mandatory=$true)][string]$Scenario)

$Base = "http://localhost:8101"

function PostProduct($strategy, $sku, $name, [hashtable]$Headers = @{}) {
    $body = @{
        sku = $sku
        name = $name
        description = "Demo product $sku"
        priceCents = (Get-Random -Min 1000 -Max 100000)
        stock = (Get-Random -Min 0 -Max 50)
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$Base/api/v1/sync/$strategy/products" `
        -ContentType "application/json" -Body $body -Headers $Headers
}

function ShowState($strategy = "all") {
    Write-Host ""
    Write-Host "----- DB vs ES ($strategy) -----" -ForegroundColor Cyan
    Invoke-RestMethod "$Base/admin/db-vs-es?strategy=$strategy" | ConvertTo-Json -Depth 5
}

switch ($Scenario) {
    "happy-path" {
        Write-Host "Writing 5 products via each strategy..."
        1..5 | ForEach-Object {
            PostProduct "naive"  "NV-$_" "Naive product $_" | Out-Null
            PostProduct "outbox" "OB-$_" "Outbox product $_" | Out-Null
            PostProduct "cdc"    "CD-$_" "CDC product $_" | Out-Null
        }
        Write-Host "Waiting 3s for async paths to catch up..."
        Start-Sleep -Seconds 3
        ShowState
    }
    "dual-write-drift" {
        Write-Host "1) Healthy write to naive — should land in both DB and ES"
        PostProduct "naive" "NV-OK" "Healthy naive" | Out-Null

        Write-Host "2) Inject 1 ES failure, then write — DB and ES will diverge"
        PostProduct "naive" "NV-FAIL" "Doomed naive" -Headers @{ "X-Inject-Failure" = "es-fail" } 2>&1 | Out-Null

        Write-Host "3) Compare counts — DB should have 1 MORE than ES"
        ShowState "naive"
    }
    "outbox-recovery" {
        Write-Host "1) Pause outbox poller — Kafka won't see new events"
        Invoke-RestMethod -Method Post "$Base/admin/outbox/poller/pause" | Out-Null

        Write-Host "2) Write 5 products — they land in DB + outbox table only"
        1..5 | ForEach-Object { PostProduct "outbox" "OB-PAUSED-$_" "Paused outbox $_" | Out-Null }
        Start-Sleep -Seconds 1
        Write-Host "Outbox stats (should show 5 pending):"
        Invoke-RestMethod "$Base/admin/outbox/stats" | ConvertTo-Json

        Write-Host ""
        Write-Host "3) Resume poller — outbox drains, ES catches up"
        Invoke-RestMethod -Method Post "$Base/admin/outbox/poller/resume" | Out-Null
        Start-Sleep -Seconds 3
        Invoke-RestMethod "$Base/admin/outbox/stats" | ConvertTo-Json
        ShowState "outbox"
    }
    "cdc-flow" {
        Write-Host "1) Initial CDC state"
        Invoke-RestMethod "$Base/admin/cdc/offset" | ConvertTo-Json

        Write-Host ""
        Write-Host "2) Write 10 products — app code doesn't touch ES"
        1..10 | ForEach-Object { PostProduct "cdc" "CD-FLOW-$_" "CDC flow $_" | Out-Null }
        Start-Sleep -Seconds 2

        Write-Host "3) CDC state after — LSN should have advanced"
        Invoke-RestMethod "$Base/admin/cdc/offset" | ConvertTo-Json
        ShowState "cdc"
    }
    default {
        Write-Host "Unknown scenario: $Scenario"
        Write-Host "Try: happy-path | dual-write-drift | outbox-recovery | cdc-flow"
        exit 1
    }
}
