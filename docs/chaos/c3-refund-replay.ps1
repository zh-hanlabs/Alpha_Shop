# W4D2 混沌 C3：退款重放 x10 并发（同用户+同订单，退款幂等键=用户+动作+订单号）
# 素材：10005 = u1001 DELIVERED 便携式露营灯×1（¥59）
# 预期：全部返回首次退款结果（同金额）；状态只迁移一次 REFUNDED；库存只还一次（+1）
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()

function Stats([string]$userId, [long]$productId) {
    (Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/stats?userId=$userId&productId=$productId" -TimeoutSec 30)
}

function QueryOrderViaTool([string]$url, [string]$userId, [string]$orderNo) {
    $body = @{userId=$userId; orderNo=$orderNo} | ConvertTo-Json
    (Invoke-RestMethod -Uri "$url/api/dev/chaos/query-order" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30)
}

$userId = 'u1001'; $productId = 7; $orderNo = '10005'
$baseline = Stats $userId $productId
$out += "=== C3 baseline ==="
$out += ("stock={0} orderCount={1} (refund target: order {2}, DELIVERED)" -f $baseline.stock, $baseline.orderCount, $orderNo)

# 场景键空间隔离 + 素材前置校验：10005 必须 DELIVERED 才构成本场景
docker exec shopagent-redis redis-cli FLUSHDB | Out-Null
$pre = QueryOrderViaTool $BaseUrl $userId $orderNo
if ($pre.data.status -ne 'DELIVERED') {
    $out += "PRECONDITION FAIL: order $orderNo status=$($pre.data.status), expect DELIVERED (restart app for fresh H2)"
    $out | Tee-Object -FilePath "$PSScriptRoot\C3-refund-replay-x10.txt"
    exit 1
}

$jobs = @()
for ($i = 1; $i -le 10; $i++) {
    $jobs += Start-Job -ScriptBlock {
        Param($n, $url, $uid, $order)
        $body = @{userId=$uid; orderNo=$order; conversationId="chaos-c3"; message="混沌C3退款-$n"} | ConvertTo-Json
        try {
            $r = Invoke-RestMethod -Uri "$url/api/dev/chaos/refund" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
            "[$n] code=$($r.code) orderNo=$($r.data.orderNo) amount=$($r.data.refundAmount) status=$($r.data.status) msg=$($r.msg)"
        } catch {
            "[$n] HTTP-FAIL: $($_.Exception.Message)"
        }
    } -ArgumentList $i, $BaseUrl, $userId, $orderNo
}
$null = Wait-Job -Job $jobs -Timeout 180
$out += "=== C3 responses (10 concurrent refund, same order) ==="
$out += ($jobs | ForEach-Object { Receive-Job -Job $_ -ErrorAction SilentlyContinue })
$jobs | Remove-Job -Force

$after = Stats $userId $productId
$out += "=== C3 after ==="
$out += ("stock={0} orderCount={1}" -f $after.stock, $after.orderCount)

# 终态：订单状态应为 REFUNDED（查询工具直连验证，独立于退款响应）
$qBody = @{userId=$userId; orderNo=$orderNo} | ConvertTo-Json
$q = Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/query-order" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($qBody)) -TimeoutSec 30
$out += "=== C3 final order status ==="
$out += ("queryOrder code={0} status={1}" -f $q.code, $q.data.status)

$respLines = $out | Where-Object { $_ -match '^\[\d+\] code=' }
$codes   = @($respLines | ForEach-Object { if ($_ -match 'code=(\d+)') { $Matches[1] } })
# 金额按数值比较：幂等结果 JSON 往返后 BigDecimal scale 变化（59.00 → 59.0），数值等价（任务清单 §7）
$amounts = @($respLines | ForEach-Object { if ($_ -match 'amount=([0-9.]+)') { [decimal]$Matches[1] } } | Select-Object -Unique)
$stockRestored = $after.stock - $baseline.stock

$out += "=== C3 verdict ==="
$out += ("successResponses={0}/{1} uniqueAmounts={2} stockRestored={3} finalStatus={4}" -f @($codes | Where-Object { $_ -eq '0' }).Count, $respLines.Count, $amounts.Count, $stockRestored, $q.data.status)
$pass = (@($codes | Where-Object { $_ -eq '0' }).Count -eq 10) -and ($amounts.Count -eq 1) -and ($stockRestored -eq 1) -and ($q.data.status -eq 'REFUNDED')
$out += if ($pass) { 'C3: PASS' } else { 'C3: FAIL' }

$out | Tee-Object -FilePath "$PSScriptRoot\C3-refund-replay-x10.txt"
if (-not $pass) { exit 1 }
