param([Parameter(Mandatory=$true)][string]$Action,
      [string]$Name = "")
$Base = "http://localhost:8113"
switch ($Action) {
    "list" {
        Invoke-RestMethod "$Base/api/v1/gotcha" | ConvertTo-Json
    }
    "explain" {
        $r = Invoke-RestMethod "$Base/api/v1/gotcha/$Name/explain"
        Write-Host $r.explanation
    }
    "run" {
        Write-Host "--- EXPLAIN: $Name ---" -ForegroundColor Cyan
        (Invoke-RestMethod "$Base/api/v1/gotcha/$Name/explain").explanation | Write-Host

        Write-Host ""
        Write-Host "--- BREAK ---" -ForegroundColor Yellow
        try {
            Invoke-RestMethod -Method Post "$Base/api/v1/gotcha/$Name/break" | ConvertTo-Json -Depth 6
        } catch {
            Write-Host "break said: $($_.Exception.Message)"
        }

        Write-Host ""
        Write-Host "--- FIX ---" -ForegroundColor Green
        Invoke-RestMethod -Method Post "$Base/api/v1/gotcha/$Name/fix" | ConvertTo-Json -Depth 6
    }
    default { Write-Host "Try: list | explain -Name X | run -Name X" }
}
