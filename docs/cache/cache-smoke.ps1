# W5D3 cache consistency smoke: detail read (fill) -> repeat (L1 hit) -> update price
# (DB first, evict L1+L2) -> read again (fresh value from DB). Evidence -> consistency-w5d3.txt
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()

function Get-Detail([long]$ProductId) {
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/dev/cache/product-detail?productId=$ProductId" -Method Get -TimeoutSec 60 -UseBasicParsing
    [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
}

function Update-Price([long]$ProductId, [string]$NewPrice) {
    $json = @{ productId = $ProductId; newPrice = $NewPrice } | ConvertTo-Json
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/dev/cache/update-product" -Method Post -ContentType 'application/json' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 60 -UseBasicParsing
    [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
}

$out += '=== step 1: first detail read (expect L2 miss -> DB -> fill, price 59.00) ==='
$out += Get-Detail 7
$out += ''
$out += '=== step 2: second detail read (expect L1 hit, 0 DB query) ==='
$out += Get-Detail 7
$out += ''
$out += '=== step 3: third detail read (still L1 hit) ==='
$out += Get-Detail 7
$out += ''
$out += '=== step 4: update price 59.00 -> 66.00 (DB update first, then L1+L2 evict) ==='
$out += Update-Price 7 '66.00'
$out += ''
$out += '=== step 5: detail read again (expect fresh 66.00 from DB, cache evicted) ==='
$out += Get-Detail 7
$out += ''
$out += '=== step 6: update price back 66.00 -> 59.00 ==='
$out += Update-Price 7 '59.00'
$out += ''
$out += '=== step 7: final read (expect 59.00 again) ==='
$out += Get-Detail 7

$out | Tee-Object -FilePath "$PSScriptRoot\consistency-w5d3.txt"
