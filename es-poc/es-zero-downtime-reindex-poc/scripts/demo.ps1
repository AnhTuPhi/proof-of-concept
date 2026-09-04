param([Parameter(Mandatory=$true)][string]$Scenario)
$Base = "http://localhost:8103"

function MakeProduct($i) {
    return @{
        sku = "DEMO-$i"
        name = "Demo product $i"
        description = "running shoes lightweight modern $i"
        priceCents = (Get-Random -Min 500 -Max 50000)
    } | ConvertTo-Json
}

switch ($Scenario) {
    "firehose" {
        Write-Host "Firehose: ~50 writes/sec. Ctrl+C to stop." -ForegroundColor Cyan
        $i = 0
        while ($true) {
            Invoke-RestMethod -Method Post -Uri "$Base/api/v1/products" -ContentType "application/json" -Body (MakeProduct $i) | Out-Null
            $i++
            if ($i % 100 -eq 0) {
                Write-Host "$(Get-Date -Format HH:mm:ss) — sent $i writes"
                $cnt = Invoke-RestMethod "$Base/api/v1/products/count"
                Write-Host "  counts: alias=$($cnt.alias) v1=$($cnt.v1) v2=$($cnt.v2)"
            }
            Start-Sleep -Milliseconds 20
        }
    }
    "reindex" {
        Write-Host "Triggering migration..."
        Invoke-RestMethod -Method Post "$Base/admin/migration/start" | ConvertTo-Json
        do {
            Start-Sleep -Seconds 2
            $s = Invoke-RestMethod "$Base/admin/migration/status"
            Write-Host ("phase={0}  created={1} updated={2}" -f $s.phase, $s.reindexCreated, $s.reindexUpdated)
        } while ($s.phase -in @("DUAL_WRITE_ENABLED", "REINDEXING", "READY_TO_SWAP", "SWAPPED"))
        Write-Host "Final phase: $($s.phase)" -ForegroundColor Green
        Invoke-RestMethod "$Base/api/v1/products/count" | ConvertTo-Json

        Write-Host ""
        Write-Host "Testing English stemmer on v2 (alias now points to v2):"
        Write-Host "  query=run  → should match 'running' docs"
        (Invoke-RestMethod "$Base/api/v1/products/search?q=run").Count
    }
    "rollback" {
        Invoke-RestMethod -Method Post "$Base/admin/migration/rollback" | ConvertTo-Json
    }
    default {
        Write-Host "Try: firehose | reindex | rollback"
    }
}
