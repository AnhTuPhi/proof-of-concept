param([Parameter(Mandatory=$true)][string]$Scenario)
$Base = "http://localhost:8112"
switch ($Scenario) {
    "firehose-bad" {
        Write-Host "Burning the cluster with leading-wildcard queries..." -ForegroundColor Yellow
        $tokens = @("ony", "msung", "ell", "ose", "pple")
        while ($true) {
            $q = $tokens | Get-Random
            try { Invoke-RestMethod "$Base/api/v1/products/wildcard?q=$q" | Out-Null } catch { }
        }
    }
    "inspect" {
        Write-Host "--- Hot threads (top of stack tells you who's busy) ---" -ForegroundColor Cyan
        (Invoke-RestMethod "$Base/admin/hot-threads").raw | Out-String | Write-Host

        Write-Host "--- Enabling slow log @ 100ms ---"
        Invoke-RestMethod -Method Post "$Base/admin/slowlog?queryMs=100&fetchMs=50" | ConvertTo-Json

        Write-Host "--- Profiling a sample query ---"
        Invoke-RestMethod "$Base/admin/profile?q=product" | ConvertTo-Json -Depth 10

        Write-Host "--- Cluster snapshot ---"
        Invoke-RestMethod "$Base/admin/diagnose" | ConvertTo-Json -Depth 6
    }
    default { Write-Host "Try: firehose-bad | inspect" }
}
