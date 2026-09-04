param([string]$Scenario = "all")
$Base = "http://localhost:8108"
switch ($Scenario) {
    "all" {
        Write-Host "--- Unfiltered: every facet shows full counts ---" -ForegroundColor Cyan
        Invoke-RestMethod "$Base/api/v1/products/search" | ConvertTo-Json -Depth 6
    }
    "brand-only" {
        Write-Host "--- brand=Apple. brand facet still shows all brands; others count only Apple ---" -ForegroundColor Cyan
        Invoke-RestMethod "$Base/api/v1/products/search?brand=Apple" | ConvertTo-Json -Depth 6
    }
    "multi" {
        Invoke-RestMethod "$Base/api/v1/products/search?brand=Apple&category=electronics&minRating=4" | ConvertTo-Json -Depth 6
    }
    default { Write-Host "Try: all | brand-only | multi" }
}
