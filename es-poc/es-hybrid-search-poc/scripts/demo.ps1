param([string]$Scenario = "compare", [string]$Q = "wireless charging phone")
$Base = "http://localhost:8109"
$encoded = [System.Web.HttpUtility]::UrlEncode($Q)
switch ($Scenario) {
    "compare" {
        Invoke-RestMethod "$Base/api/v1/search/compare?q=$encoded&k=5" | ConvertTo-Json -Depth 6
    }
    "lexical" { Invoke-RestMethod "$Base/api/v1/search/lexical?q=$encoded" | ConvertTo-Json }
    "knn"     { Invoke-RestMethod "$Base/api/v1/search/knn?q=$encoded"     | ConvertTo-Json }
    "hybrid"  { Invoke-RestMethod "$Base/api/v1/search/hybrid?q=$encoded"  | ConvertTo-Json }
}
