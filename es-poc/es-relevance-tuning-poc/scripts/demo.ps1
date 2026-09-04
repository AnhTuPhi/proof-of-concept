param([string]$Scenario = "eval")
$Base = "http://localhost:8106"
switch ($Scenario) {
    "eval" {
        Write-Host "--- NDCG@10 + MRR: baseline vs tuned ---" -ForegroundColor Cyan
        $r = Invoke-RestMethod "$Base/api/v1/eval/run"
        foreach ($c in $r.configs) {
            Write-Host ("{0,-12} NDCG@10={1:N3}  MRR={2:N3}" -f $c.config, $c.avgNdcg10, $c.avgMrr) -ForegroundColor Green
        }
        Write-Host ""
        Write-Host "Per-query (tuned):"
        ($r.configs | Where-Object { $_.config -eq "tuned" }).perQuery | Format-Table query, ndcg10, mrr -AutoSize
    }
    "iphone" {
        Write-Host "baseline (match on name only):"
        Invoke-RestMethod "$Base/api/v1/search?q=iphone&config=baseline" | Format-Table id, name, score -AutoSize
        Write-Host "tuned (multi_match + popularity boost):"
        Invoke-RestMethod "$Base/api/v1/search?q=iphone&config=tuned"    | Format-Table id, name, score -AutoSize
    }
    default { Write-Host "Try: eval | iphone" }
}
