param(
  [string]$Url = 'http://localhost:3001/orders',
  [int]$Iterations = 50
)

$skus = @('WIDGET-1', 'WIDGET-2', 'GADGET-7', 'OUT-OF-STOCK')

for ($i = 1; $i -le $Iterations; $i++) {
  $sku    = $skus | Get-Random
  $qty    = Get-Random -Minimum 1 -Maximum 5
  $amount = Get-Random -Minimum 100 -Maximum 9000

  # ~5% of requests are intentionally malformed to surface 4xx traces
  if ((Get-Random -Minimum 0 -Maximum 20) -eq 0) {
    $body = '{"sku":"WIDGET-1"}'
  } else {
    $body = "{`"sku`":`"$sku`",`"qty`":$qty,`"amount`":$amount}"
  }

  Write-Output "[$i/$Iterations] $body"
  try {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-WebRequest -Uri $Url -Method Post -Body $body -ContentType 'application/json' -UseBasicParsing -ErrorAction Stop
    $sw.Stop()
    Write-Output ("  -> {0} in {1:N3}s" -f $resp.StatusCode, ($sw.Elapsed.TotalSeconds))
  } catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Output ("  -> {0} ({1})" -f $code, $_.Exception.Message)
  }

  Start-Sleep -Milliseconds 200
}
