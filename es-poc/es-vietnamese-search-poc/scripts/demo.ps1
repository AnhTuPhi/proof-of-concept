$Base = "http://localhost:8105"
$queries = @(
    "dien thoai",       # without diacritics — standard fails, folded matches
    "Đà Nẵng",
    "ca phe",
    "may tinh bang",
    "ban phim"
)
foreach ($q in $queries) {
    Write-Host ("--- query: {0}" -f $q) -ForegroundColor Cyan
    $r = Invoke-RestMethod -Uri ("$Base/api/v1/products/compare?q=" + [System.Web.HTTPUtility]::UrlEncode($q))
    $r | ConvertTo-Json -Depth 6
}
