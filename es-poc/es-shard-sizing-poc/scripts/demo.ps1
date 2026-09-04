param([Parameter(Mandatory=$true)][string]$Scenario,
      [double]$DailyGb = 10, [int]$RetentionDays = 30, [double]$TargetShardGb = 30, [int]$Keys = 1500)
$Base = "http://localhost:8111"
switch ($Scenario) {
    "calculator" {
        Invoke-RestMethod "$Base/api/v1/sizing/calculator?dailyGb=$DailyGb&retentionDays=$RetentionDays&targetShardGb=$TargetShardGb" | ConvertTo-Json -Depth 4
    }
    "ilm" {
        Invoke-RestMethod -Method Post "$Base/api/v1/sizing/ilm/install" | ConvertTo-Json
        Invoke-RestMethod -Method Post "$Base/api/v1/sizing/ilm/load?count=20000" | ConvertTo-Json
        Write-Host "Check rollover: curl -s http://localhost:9200/_cat/indices/audit-*?v"
    }
    "explode" {
        Invoke-RestMethod -Method Post "$Base/api/v1/sizing/explode-mapping?keys=$Keys" | ConvertTo-Json
    }
    "explode-fix" {
        Invoke-RestMethod -Method Post "$Base/api/v1/sizing/explode-mapping/fix?keys=$Keys" | ConvertTo-Json
    }
    default { Write-Host "Try: calculator | ilm | explode | explode-fix" }
}
