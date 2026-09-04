param(
    [Parameter(Mandatory=$true)][string]$Scenario,
    [int]$Count = 100000,
    [int]$Parallelism = 4
)
$Base = "http://localhost:8104"

function Run($strat, $cnt) {
    Write-Host "Running $strat with count=$cnt..." -ForegroundColor Cyan
    $r = Invoke-RestMethod -Method Post "$Base/api/v1/bulk/run?strategy=$strat&count=$cnt&parallelism=$Parallelism"
    Write-Host ("  {0,-15} {1} docs in {2,5} ms  →  {3,9} docs/sec" -f $strat, $r.docs, $r.elapsedMs, [int]$r.docsPerSec) -ForegroundColor Green
    return $r
}

switch ($Scenario) {
    "all" {
        Run "SINGLE"        ([Math]::Min(10000, $Count)) | Out-Null  # single is so slow we cap it
        Run "BULK_DEFAULT"  $Count | Out-Null
        Run "BULK_TUNED"    $Count | Out-Null
        Run "BULK_PARALLEL" $Count | Out-Null
        Write-Host ""
        Write-Host "Final scoreboard:"
        Invoke-RestMethod "$Base/api/v1/bulk/results" | ConvertTo-Json -Depth 4
    }
    "single"        { Run "SINGLE"        $Count }
    "bulk-default"  { Run "BULK_DEFAULT"  $Count }
    "bulk-tuned"    { Run "BULK_TUNED"    $Count }
    "bulk-parallel" { Run "BULK_PARALLEL" $Count }
    default {
        Write-Host "Try: all | single | bulk-default | bulk-tuned | bulk-parallel"
        Write-Host "Options: -Count N (default 100000), -Parallelism N (default 4)"
    }
}
