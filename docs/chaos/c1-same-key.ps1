# W4D1 混沌 C1：同键重放 x10 并发（同用户+同商品+同会话+同指令摘要）
# 预期：只落 1 单；全部返回首次结果（同一订单号）；库存精确扣 1
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()

function Stats([string]$userId, [long]$productId) {
    (Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/stats?userId=$userId&productId=$productId" -TimeoutSec 30)
}

$userId = 'u1001'; $productId = 7
$baseline = Stats $userId $productId
$out += "=== C1 baseline ==="
$out += ("stock={0} orderCount={1} (user={2}, product={3})" -f $baseline.stock, $baseline.orderCount, $userId, $productId)

# 清掉历史幂等键，保证本场景键空间干净（H2 是事实源，Redis 仅缓存幂等标记）
docker exec shopagent-redis redis-cli FLUSHDB | Out-Null

$conversationId = 'chaos-c1-20261003'
$message = '混沌C1同键下单'   # 10 路完全一致 → 同一幂等键

$jobs = @()
for ($i = 1; $i -le 10; $i++) {
    $jobs += Start-Job -ScriptBlock {
        Param($n, $url, $uid, $pid2, $cid, $msg)
        $body = @{userId=$uid; productId=$pid2; quantity=1; conversationId=$cid; message=$msg} | ConvertTo-Json
        try {
            $r = Invoke-RestMethod -Uri "$url/api/dev/chaos/place" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
            "[$n] code=$($r.code) orderNo=$($r.data.orderNo) msg=$($r.msg)"
        } catch {
            "[$n] HTTP-FAIL: $($_.Exception.Message)"
        }
    } -ArgumentList $i, $BaseUrl, $userId, $productId, $conversationId, $message
}
$null = Wait-Job -Job $jobs -Timeout 180
$out += "=== C1 responses (10 concurrent, same idempotent key) ==="
$out += ($jobs | ForEach-Object { Receive-Job -Job $_ -ErrorAction SilentlyContinue })
$jobs | Remove-Job -Force

$after = Stats $userId $productId
$out += "=== C1 after ==="
$out += ("stock={0} orderCount={1}" -f $after.stock, $after.orderCount)

# 判定
$orderNos = @($jobs | Out-Null)
$respLines = $out | Where-Object { $_ -match '^\[\d+\] code=' }
$codes = @($respLines | ForEach-Object { if ($_ -match 'code=(\d+)') { $Matches[1] } })
$nos   = @($respLines | ForEach-Object { if ($_ -match 'orderNo=([0-9]+)') { $Matches[1] } } | Select-Object -Unique)
$newOrders = $after.orderCount - $baseline.orderCount
$stockDelta = $baseline.stock - $after.stock

$out += "=== C1 verdict ==="
$out += ("successResponses={0}/{1} uniqueOrderNos={2} newOrders={3} stockDelta={4}" -f @($codes | Where-Object { $_ -eq '0' }).Count, $respLines.Count, $nos.Count, $newOrders, $stockDelta)
$pass = (@($codes | Where-Object { $_ -eq '0' }).Count -eq 10) -and ($nos.Count -eq 1) -and ($newOrders -eq 1) -and ($stockDelta -eq 1)
$out += if ($pass) { 'C1: PASS' } else { 'C1: FAIL' }

$out | Tee-Object -FilePath "$PSScriptRoot\C1-same-key-x10.txt"
if (-not $pass) { exit 1 }
