# W4D2 混沌 C4：Redis 停机 fail-closed（docker stop 期间）
# 预期：交易工具「交易暂不可用」（code 50001）；查询链路（纯 H2）不受影响；Redis 恢复后交易自动可用
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()
$fail = $false

function Place([string]$cid, [string]$msg) {
    $body = @{userId='u1001'; productId=7; quantity=1; conversationId=$cid; message=$msg} | ConvertTo-Json
    Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/place" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120
}

# PS5 的 Invoke-RestMethod 对无 charset 的 JSON 响应按 ISO-8859-1 解码，中文 msg 会成 mojibake；
# UTF-8 字节按 Latin-1 还原回正确文本（ASCII 字符往返无损）
function FixMsg([string]$s) {
    if ($s -match 'ä|å|æ') {
        return [Text.Encoding]::UTF8.GetString([Text.Encoding]::GetEncoding('ISO-8859-1').GetBytes($s))
    }
    return $s
}

function QueryOrder([string]$orderNo) {
    $body = @{userId='u1001'; orderNo=$orderNo} | ConvertTo-Json
    Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/query-order" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
}

$out += "=== C4 step 1: stop redis ==="
# 停机前清键：保证停机场景的 place 走全新幂等键（peek miss → 锁 → fail-closed 完整路径）
docker exec shopagent-redis redis-cli FLUSHDB | Out-Null
docker stop shopagent-redis | Out-Null
$out += "redis stopped"
Start-Sleep -Seconds 2

$out += "=== C4 step 2: place order while redis DOWN (expect fail-closed 50001) ==="
try {
    $p = Place 'chaos-c4-20261003' '混沌C4停机下单'
    $out += ("place code={0} msg={1}" -f $p.code, (FixMsg $p.msg))
    if ($p.code -ne 50001) { $fail = $true; $out += 'UNEXPECTED: not fail-closed!' }
} catch {
    $fail = $true; $out += "place HTTP-FAIL: $($_.Exception.Message)"
}

$out += "=== C4 step 3: query order while redis DOWN (expect success, pure H2) ==="
try {
    $q = QueryOrder '10003'
    $out += ("queryOrder code={0} orderNo={1} status={2}" -f $q.code, $q.data.orderNo, $q.data.status)
    if ($q.code -ne 0) { $fail = $true; $out += 'UNEXPECTED: query affected by redis down!' }
} catch {
    $fail = $true; $out += "query HTTP-FAIL: $($_.Exception.Message)"
}

$out += "=== C4 step 4: stats while redis DOWN (expect success, pure H2) ==="
try {
    $s = Invoke-RestMethod -Uri "$BaseUrl/api/dev/chaos/stats?userId=u1001&productId=7" -TimeoutSec 30
    $out += ("stats stock={0} orderCount={1}" -f $s.stock, $s.orderCount)
} catch {
    $fail = $true; $out += "stats HTTP-FAIL: $($_.Exception.Message)"
}

$out += "=== C4 step 5: restart redis ==="
docker start shopagent-redis | Out-Null
$out += "redis restarted, waiting for redis to accept commands"
$pong = $false
for ($i = 0; $i -lt 15; $i++) {
    if ((docker exec shopagent-redis redis-cli PING 2>$null) -match 'PONG') { $pong = $true; break }
    Start-Sleep -Seconds 1
}
$out += "redis ping ok: $pong"
# 恢复场景用全新键：走完整闸序而非重放，才是真正的恢复证明
docker exec shopagent-redis redis-cli FLUSHDB | Out-Null
Start-Sleep -Seconds 3

$out += "=== C4 step 6: place order after redis RECOVERED (expect success) ==="
try {
    $p2 = Place 'chaos-c4-20261003' '混沌C4恢复后下单'
    $out += ("place code={0} orderNo={1}" -f $p2.code, $p2.data.orderNo)
    if ($p2.code -ne 0) { $fail = $true; $out += 'UNEXPECTED: not recovered!' }
} catch {
    $fail = $true; $out += "place HTTP-FAIL: $($_.Exception.Message)"
}

$out += "=== C4 verdict ==="
$out += if ($fail) { 'C4: FAIL' } else { 'C4: PASS' }

$out | Tee-Object -FilePath "$PSScriptRoot\C4-redis-down.txt"
if ($fail) { exit 1 }
