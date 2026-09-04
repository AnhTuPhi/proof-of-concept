param([string]$Scenario = "compare", [string]$Q = "iph")
$Base = "http://localhost:8107"

switch ($Scenario) {
    "compare" {
        Invoke-RestMethod "$Base/api/v1/suggest/compare?q=$Q&size=5" | ConvertTo-Json -Depth 6
    }
    "bench" {
        $queries = @("ip", "iph", "ipho", "iphone", "sam", "samsung", "samsng", "kindle", "appl", "macbook")
        foreach ($q in $queries) {
            $n = Invoke-RestMethod "$Base/api/v1/suggest/ngram?q=$q&size=5"
            $c = Invoke-RestMethod "$Base/api/v1/suggest/completion?q=$q&size=5"
            $s = Invoke-RestMethod "$Base/api/v1/suggest/sayt?q=$q&size=5"
            Write-Host ("q={0,-10} ngram={1,3}ms ({4} hits)  completion={2,3}ms ({5} hits)  sayt={3,3}ms ({6} hits)" -f `
                $q, $n.tookMs, $c.tookMs, $s.tookMs, $n.suggestions.Count, $c.suggestions.Count, $s.suggestions.Count)
        }
    }
}
