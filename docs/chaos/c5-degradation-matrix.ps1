# W5D5 degradation matrix: with Redis DOWN -> trade fail-closed / knowledge degrade (chat alive) /
# cache fall through to DB; after Redis RECOVERS -> all self-heal. Evidence -> C5-degradation-matrix-w5.txt
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()
$chatBody = [System.Text.Encoding]::UTF8.GetBytes('{"conversationId":"matrix-1","message":"露营灯防水吗"}')

function Post-Json([string]$Url, [string]$Json) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Json)
    try {
        $resp = Invoke-WebRequest -Uri $Url -Method Post -ContentType 'application/json' -Body $bytes -TimeoutSec 120 -UseBasicParsing
        [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } catch {
        'HTTP-FAIL: ' + $_.Exception.Message
    }
}

function Get-Detail([long]$ProductId) {
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/dev/cache/product-detail?productId=$ProductId" -Method Get -TimeoutSec 60 -UseBasicParsing
        [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } catch {
        'HTTP-FAIL: ' + $_.Exception.Message
    }
}

$out += '=== matrix A: trade while Redis DOWN (expect fail-closed 交易暂不可用) ==='
$out += Post-Json "$BaseUrl/api/dev/chaos/place" '{"userId":"u1001","productId":3,"quantity":1,"conversationId":"matrix-a","message":"matrix trade attempt"}'
$out += ''
$out += '=== matrix B: product detail while Redis DOWN, uncached id=1 (expect DB fall-through, price 129.00) ==='
$out += Get-Detail 1
$out += ''
$out += '=== matrix C: knowledge QA while Redis DOWN (expect degraded answer, chat alive, no crash) ==='
$out += Post-Json "$BaseUrl/api/chat" (@{ conversationId = 'matrix-1'; message = '露营灯防水吗' } | ConvertTo-Json)
$out += ''
$out += '=== recover Redis ==='
docker start shopagent-redis | Out-Null
# W6D5 回归修复：原写法 ForEach-Object 内 break 在无外层循环时会静默终止整个脚本
# （首轮 PING 即 PONG 时必触发，Tee 永不执行）——for 循环 + 标志位是安全等价
$pinged = $false
for ($i = 0; $i -lt 20 -and -not $pinged; $i++) {
    $ok = docker exec shopagent-redis redis-cli PING 2>$null
    if ("$ok" -match 'PONG') { $pinged = $true } else { Start-Sleep -Seconds 2 }
}
$out += "redis ping: $(docker exec shopagent-redis redis-cli PING)"
$out += ''
$out += '=== matrix D: trade after recovery (expect success orderNo) ==='
$out += Post-Json "$BaseUrl/api/dev/chaos/place" '{"userId":"u1001","productId":3,"quantity":1,"conversationId":"matrix-d","message":"matrix trade after recovery"}'
$out += ''
$out += '=== matrix E: knowledge QA after recovery (expect IPX5 facts again) ==='
$out += Post-Json "$BaseUrl/api/chat" (@{ conversationId = 'matrix-2'; message = '露营灯防水吗' } | ConvertTo-Json)

$out | Tee-Object -FilePath "$PSScriptRoot\C5-degradation-matrix-w5.txt"
