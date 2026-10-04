# W6D1 rate-limit burst smoke (4 scenarios).
# A: same user x10 concurrent  -> user bucket 2/1s -> expect 2x200 + 8x429
# B: two users x10 each        -> independent buckets -> each 2x200 + 8x429
# C: 12 fresh users x1         -> global bucket 10/1s -> expect 10x200 + 2x429
# D: /api/chat/stream x3       -> 2x SSE answer+done, 1x SSE error+done (raw body saved for UTF-8 evidence)
# Burst model: Start-Job (W4 pattern) + go-file barrier, but each job fires ALL its requests
# with HttpClient.SendAsync in one tight loop (<100ms spread) so the whole volley lands in a
# single 1s window - per-request job-process wakeup jitter (>1s for 20 jobs) was observed to
# tear the window open in a previous run. Script avoids non-ASCII literals: PS5.1 reads
# BOM-less UTF-8 scripts as ANSI/GBK. LLM: local stub (w6d1-llm-stub.jsh), gate semantics
# independent of the real LLM.
param([string]$BaseUrl = 'http://localhost:8081')

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http   # PS5.1 不预载 HttpClient 所在程序集
$out = @()
$out += "=== W6D1 rate-limit burst smoke ==="
$out += "baseUrl=$BaseUrl start=$(Get-Date -Format 'HH:mm:ss.fff')"

function Reset-Buckets {
    # flushdb (same as W4 C2): rlimit keys incl. internal hash-tag keys + idempotency keys,
    # then wait out the 1s window
    docker exec shopagent-redis redis-cli FLUSHDB | Out-Null
    Start-Sleep -Milliseconds 1200
}

# one job: wait for barrier, fire $PerUser requests per user in $UserIds via HttpClient volley,
# return "uid-i status=code" lines (stream=true additionally saves raw SSE bodies)
$jobScript = {
    Param($url, $userIds, $perUser, $conv, $goPath, $endpoint, $tag, $rawPath)
    Add-Type -AssemblyName System.Net.Http   # job 是独立 runspace，需自行加载
    try {
        while (-not (Test-Path $goPath)) { Start-Sleep -Milliseconds 20 }
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromSeconds(90)
        $tasks = @()
        $meta = @()
        foreach ($uid in $userIds) {
            for ($i = 1; $i -le $perUser; $i++) {
                $json = '{"userId":"' + $uid + '","conversationId":"' + $conv + '","message":"w6d1-' + $uid + '-' + $i + '"}'
                $msg = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$url$endpoint")
                $msg.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
                $tasks += $client.SendAsync($msg)
                $meta += "$uid-$i"   # 文件名后缀只容 uid-i：Windows 路径不容 |，行前缀拼行时再加
            }
        }
        # 显式 cast：Task[]+timeout 重载在 PS5 下绑定失败（曾致 job 静默全灭）
        [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
        $lines = @()
        for ($i = 0; $i -lt $tasks.Count; $i++) {
            $t = $tasks[$i]
            if ($t.Status -eq [System.Threading.Tasks.TaskStatus]::RanToCompletion) {
                $code = [int]$t.Result.StatusCode
                if ($rawPath -and $code -eq 200) {
                    $bytes = $t.Result.Content.ReadAsByteArrayAsync().Result
                    [System.IO.File]::WriteAllBytes("$rawPath-$($meta[$i]).txt", $bytes)
                }
                $lines += "$tag|$($meta[$i]) status=$code"
            } else {
                $lines += "$tag|$($meta[$i]) status=ERR ($($t.Exception.GetBaseException().Message))"
            }
        }
        $client.Dispose()
        return $lines
    } catch {
        return "$tag|JOB-ERROR: $($_.Exception.Message) @ $($_.InvocationInfo.PositionMessage)"
    }
}

function Start-Volley {
    Param([string[]]$UserIds, [int]$PerUser, [string]$Tag, [string]$Endpoint = '/api/chat', [string]$RawPath = '')
    $go = "$env:TEMP\w6d1-go.flag"
    Remove-Item $go -ErrorAction SilentlyContinue
    $job = Start-Job -ScriptBlock $jobScript -ArgumentList $BaseUrl, $UserIds, $PerUser, 'w6d1-smoke', $go, $Endpoint, $Tag, $RawPath
    Start-Sleep -Milliseconds 400
    New-Item -ItemType File -Path $go -Force | Out-Null
    $null = Wait-Job -Job $job -Timeout 150
    $lines = Receive-Job -Job $job -ErrorAction SilentlyContinue
    Remove-Job -Job $job -Force
    return $lines
}

function Count-Codes([object[]]$Lines) {
    $codes = @($Lines | ForEach-Object { if ($_ -match 'status=(\d+)') { $Matches[1] } })
    return @(@($codes | Where-Object { $_ -eq '200' }).Count, @($codes | Where-Object { $_ -eq '429' }).Count)
}

# ---- Scenario A ----
Reset-Buckets
$a = Start-Volley -UserIds @('u1001') -PerUser 10 -Tag 'A'
$out += "--- A: u1001 x10 ---"
$out += $a
$a200, $a429 = Count-Codes $a
$passA = ($a200 -eq 2) -and ($a429 -eq 8)
$out += "A verdict: 200=$a200 429=$a429 -> $(if ($passA) { 'PASS' } else { 'FAIL' })"

# ---- Scenario B: two parallel jobs, one per user ----
Reset-Buckets
$go = "$env:TEMP\w6d1-go.flag"
Remove-Item $go -ErrorAction SilentlyContinue
$jobUa = Start-Job -ScriptBlock $jobScript -ArgumentList $BaseUrl, @('ua'), 10, 'w6d1-smoke', $go, '/api/chat', 'B', ''
$jobUb = Start-Job -ScriptBlock $jobScript -ArgumentList $BaseUrl, @('ub'), 10, 'w6d1-smoke', $go, '/api/chat', 'B', ''
Start-Sleep -Milliseconds 600
New-Item -ItemType File -Path $go -Force | Out-Null
$null = Wait-Job -Job @($jobUa, $jobUb) -Timeout 150
$bUa = Receive-Job -Job $jobUa -ErrorAction SilentlyContinue
$bUb = Receive-Job -Job $jobUb -ErrorAction SilentlyContinue
Remove-Job -Job @($jobUa, $jobUb) -Force
$out += "--- B: ua x10 / ub x10 (parallel) ---"
$out += $bUa
$out += $bUb
$ua200, $ua429 = Count-Codes $bUa
$ub200, $ub429 = Count-Codes $bUb
$passB = ($ua200 -eq 2) -and ($ua429 -eq 8) -and ($ub200 -eq 2) -and ($ub429 -eq 8)
$out += "B verdict: ua 200=$ua200 429=$ua429 / ub 200=$ub200 429=$ub429 -> $(if ($passB) { 'PASS' } else { 'FAIL' })"

# ---- Scenario C ----
Reset-Buckets
$cUsers = @(1..12 | ForEach-Object { "burst-$_" })
$c = Start-Volley -UserIds $cUsers -PerUser 1 -Tag 'C'
$out += "--- C: burst-1..12 x1 (fresh user buckets, global bucket only) ---"
$out += $c
$c200, $c429 = Count-Codes $c
$passC = ($c200 -eq 10) -and ($c429 -eq 2)
$out += "C verdict: 200=$c200 429=$c429 -> $(if ($passC) { 'PASS' } else { 'FAIL' })"

# ---- Scenario D ----
Reset-Buckets
$raw = "$PSScriptRoot\w6d1-sse"
$d = Start-Volley -UserIds @('u1001') -PerUser 3 -Tag 'D' -Endpoint '/api/chat/stream' -RawPath $raw
$out += "--- D: /api/chat/stream x3 ---"
$out += $d
$dFiles = @(Get-ChildItem "$raw-*.txt" -ErrorAction SilentlyContinue)
$answerBodies = @($dFiles | Where-Object { [System.IO.File]::ReadAllText($_.FullName) -match 'event:answer' })
$errorBodies = @($dFiles | Where-Object { $rawBody = [System.IO.File]::ReadAllText($_.FullName); ($rawBody -match 'event:error') -and ($rawBody -match 'event:done') })
$passD = ($answerBodies.Count -ge 2) -and ($errorBodies.Count -ge 1)
$out += "D verdict: answerSSE=$($answerBodies.Count) errorSSE(=done)=$($errorBodies.Count) -> $(if ($passD) { 'PASS' } else { 'FAIL' })"
$out += "D raw SSE bodies saved: $raw-*.txt (rate-limited body contains event:error with CJK message + event:done, verified server-side UTF-8)"

$out += "=== verdicts ==="
$out += "A=$(if ($passA) { 'PASS' } else { 'FAIL' }) B=$(if ($passB) { 'PASS' } else { 'FAIL' }) C=$(if ($passC) { 'PASS' } else { 'FAIL' }) D=$(if ($passD) { 'PASS' } else { 'FAIL' })"
$out += "end=$(Get-Date -Format 'HH:mm:ss.fff')"

$out | Tee-Object -FilePath "$PSScriptRoot\w6d1-rate-limit-smoke.txt"
if (-not ($passA -and $passB -and $passC -and $passD)) { exit 1 }
