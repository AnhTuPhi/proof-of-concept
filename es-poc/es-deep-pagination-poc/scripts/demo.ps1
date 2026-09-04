param([Parameter(Mandatory=$true)][string]$Scenario)
$Base = "http://localhost:8102"

function TimePage($url) {
    $r = Invoke-RestMethod -Uri $url
    Write-Host ("{0,-60} took={1,5}ms items={2}" -f $url, $r.tookMillis, $r.items.Count)
    return $r
}

switch ($Scenario) {
    "baseline" {
        Write-Host "--- from+size at increasing depth ---"
        TimePage "$Base/api/v1/products/page?page=1&size=20"   | Out-Null
        TimePage "$Base/api/v1/products/page?page=10&size=20"  | Out-Null
        TimePage "$Base/api/v1/products/page?page=100&size=20" | Out-Null
        TimePage "$Base/api/v1/products/page?page=499&size=20" | Out-Null  # ~10k hard cap
    }
    "break-it" {
        Write-Host "--- from+size past the wall ---"
        try {
            TimePage "$Base/api/v1/products/page?page=600&size=20"
        } catch {
            Write-Host "Got expected error: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    "sweet-spot" {
        Write-Host "--- search_after through many pages ---"
        $cursor = $null
        for ($i = 1; $i -le 20; $i++) {
            $url = if ($cursor) { "$Base/api/v1/products/scroll?size=20&cursor=$cursor" }
                   else         { "$Base/api/v1/products/scroll?size=20" }
            $r = TimePage $url
            $cursor = $r.nextCursor
            if (-not $cursor) { break }
        }
    }
    "export" {
        Write-Host "--- PIT export, all docs in 500-doc chunks ---"
        $cursor = $null; $total = 0; $t0 = Get-Date
        do {
            $url = if ($cursor) { "$Base/api/v1/products/export?size=500&cursor=$cursor" }
                   else         { "$Base/api/v1/products/export?size=500" }
            $r = Invoke-RestMethod $url
            $total += $r.items.Count
            $cursor = $r.nextCursor
        } while ($cursor)
        $elapsed = (Get-Date) - $t0
        Write-Host ("Exported {0} docs in {1:N1}s" -f $total, $elapsed.TotalSeconds)
    }
    default {
        Write-Host "Try: baseline | break-it | sweet-spot | export"
        exit 1
    }
}
