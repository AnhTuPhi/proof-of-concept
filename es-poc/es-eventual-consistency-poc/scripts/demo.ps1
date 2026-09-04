param([Parameter(Mandatory=$true)][string]$Scenario)
$Base = "http://localhost:8110"

function NewProduct() {
    @{ sku = "DEMO-" + (Get-Random); name = "Demo product"; priceCents = (Get-Random -Min 100 -Max 10000) } | ConvertTo-Json
}

switch ($Scenario) {
    "collapse" {
        Write-Host "default mode — write then immediately get (probably miss)" -ForegroundColor Cyan
        for ($i = 1; $i -le 10; $i++) {
            $r = Invoke-RestMethod -Method Post "$Base/api/v1/products?mode=default" -ContentType "application/json" -Body (NewProduct)
            $id = $r.product.id
            $g  = Invoke-RestMethod "$Base/api/v1/products/$id"
            Write-Host ("  attempt {0,2} written={1} (writeMs={2})  ES.found={3}" -f $i, $id, $r.elapsedMs, $g.found)
        }
    }
    "wait-for" {
        Write-Host "wait_for mode — 100% read-after-write, write is slow"
        for ($i = 1; $i -le 5; $i++) {
            $r = Invoke-RestMethod -Method Post "$Base/api/v1/products?mode=wait-for" -ContentType "application/json" -Body (NewProduct)
            $g = Invoke-RestMethod "$Base/api/v1/products/$($r.product.id)"
            Write-Host ("  writeMs={0,4}  read.found={1}" -f $r.elapsedMs, $g.found)
        }
    }
    "force-refresh" {
        Write-Host "force-refresh mode — works but expensive at scale"
        for ($i = 1; $i -le 5; $i++) {
            $r = Invoke-RestMethod -Method Post "$Base/api/v1/products?mode=force-refresh" -ContentType "application/json" -Body (NewProduct)
            $g = Invoke-RestMethod "$Base/api/v1/products/$($r.product.id)"
            Write-Host ("  writeMs={0,4}  read.found={1}" -f $r.elapsedMs, $g.found)
        }
    }
    "read-through" {
        Write-Host "Write in default mode, then read-through (DB fallback)"
        $r = Invoke-RestMethod -Method Post "$Base/api/v1/products?mode=default" -ContentType "application/json" -Body (NewProduct)
        $id = $r.product.id
        $a = Invoke-RestMethod "$Base/api/v1/products/$id?mode=es-only"
        Write-Host ("  es-only      found={0}" -f $a.found)
        $b = Invoke-RestMethod "$Base/api/v1/products/$id?mode=read-through"
        Write-Host ("  read-through found={1}" -f 0, $b.found)
    }
    "version-skew" {
        $id = "VS-" + (Get-Random)
        $r = Invoke-RestMethod -Method Post "$Base/api/v1/products/version-demo/$id"
        Write-Host "Wrote three versions out of order. Current doc:"
        $r | ConvertTo-Json -Depth 4
        Write-Host "Should equal 'Newest version' regardless of order."
    }
    default {
        Write-Host "Try: collapse | wait-for | force-refresh | read-through | version-skew"
    }
}
