# W4D1 混沌 C2：异键并发 x50（同用户+同商品+同会话，消息各异 → 指令摘要不同 → 幂等键不同）
# 预期：50 单全部落库、库存精确扣 50、零超卖（同锁键串行 + 条件扣减双防线）
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()

function Stats([string]$userId, [long]$productId) {
    (Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/stats?userId=$userId&productId=$productId" -TimeoutSec 30)
}

$userId = 'u1001'; $productId = 7
$baseline = Stats $userId $productId
$out += "=== C2 baseline ==="
$out += ("stock={0} orderCount={1} (user={2}, product={3})" -f $baseline.stock, $baseline.orderCount, $userId, $productId)

$conversationId = 'chaos-c2-20261003'

$jobs = @()
for ($i = 1; $i -le 50; $i++) {
    $jobs += Start-Job -ScriptBlock {
        Param($n, $url, $uid, $pid2, $cid)
        $msg = "混沌C2异键下单-$n"   # 每路消息不同 → instructionDigest 不同 → 新幂等键（正常复购语义）
        $body = @{userId=$uid; productId=$pid2; quantity=1; conversationId=$cid; message=$msg} | ConvertTo-Json
        try {
            $r = Invoke-RestMethod -Uri "$url/api/dev/chaos/place" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120
            "[$n] code=$($r.code) orderNo=$($r.data.orderNo) msg=$($r.msg)"
        } catch {
            "[$n] HTTP-FAIL: $($_.Exception.Message)"
        }
    } -ArgumentList $i, $BaseUrl, $userId, $productId, $conversationId
}
$null = Wait-Job -Job $jobs -Timeout 300
$out += "=== C2 responses (50 concurrent, 50 distinct idempotent keys) ==="
$out += ($jobs | ForEach-Object { Receive-Job -Job $_ -ErrorAction SilentlyContinue })
$jobs | Remove-Job -Force

$after = Stats $userId $productId
$out += "=== C2 after ==="
$out += ("stock={0} orderCount={1}" -f $after.stock, $after.orderCount)

$respLines = $out | Where-Object { $_ -match '^\[\d+\] code=' }
$codes = @($respLines | ForEach-Object { if ($_ -match 'code=(\d+)') { $Matches[1] } })
$nos   = @($respLines | ForEach-Object { if ($_ -match 'orderNo=([0-9]+)') { $Matches[1] } } | Select-Object -Unique)
$newOrders = $after.orderCount - $baseline.orderCount
$stockDelta = $baseline.stock - $after.stock

$out += "=== C2 verdict ==="
$out += ("successResponses={0}/{1} uniqueOrderNos={2} newOrders={3} stockDelta={4} finalStock={5}" -f @($codes | Where-Object { $_ -eq '0' }).Count, $respLines.Count, $nos.Count, $newOrders, $stockDelta, $after.stock)
$pass = (@($codes | Where-Object { $_ -eq '0' }).Count -eq 50) -and ($nos.Count -eq 50) -and ($newOrders -eq 50) -and ($stockDelta -eq 50) -and ($after.stock -ge 0)
$out += if ($pass) { 'C2: PASS' } else { 'C2: FAIL' }

$out | Tee-Object -FilePath "$PSScriptRoot\C2-diff-keys-x50.txt"
if (-not $pass) { exit 1 }
