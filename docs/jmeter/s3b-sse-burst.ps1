param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$Threads = 10,
    [int]$ReqsPerThread = 3,
    [string]$UserPrefix = 'ulsse',
    [string]$OutFile = ''
)

# W7D2 T2.1 S3b SSE burst (PS5 fallback - jmeter-sse-sampler 2.0.1 has no POST body, w7d0 F4).
# Each job = one virtual user firing ReqsPerThread SEQUENTIAL POST /api/chat/stream requests,
# streaming the SSE body and recording per-request: ttfb (first event:answer), total ms,
# answer event count, done-tail flag, error-event flag (rate-limit speech when gate ON).
# Conventions from W6 PS5 pits: UTF-8 BOM file, ASCII-only literals, Add-Type inside each
# job (separate runspace), go-file barrier to align start.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$out = @()
$out += "=== W7D2 S3b SSE burst ==="
$out += "baseUrl=$BaseUrl threads=$Threads reqsPerThread=$ReqsPerThread userPrefix=$UserPrefix start=$(Get-Date -Format 'HH:mm:ss.fff')"

$jobScript = {
    Param($url, $tag, $reqCount, $goPath)
    Add-Type -AssemblyName System.Net.Http
    try {
        while (-not (Test-Path $goPath)) { Start-Sleep -Milliseconds 20 }
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromSeconds(120)
        $lines = @()
        for ($i = 1; $i -le $reqCount; $i++) {
            $uid = "$tag-u$i"
            $cid = "$tag-c$i"
            $json = '{"conversationId":"' + $cid + '","message":"hello-stub-sse","userId":"' + $uid + '"}'
            $msg = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$url/api/chat/stream")
            $msg.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $status = 0; $ttfb = -1; $answers = 0; $done = 0; $errEvt = 0; $evtErr = ''
            try {
                $resp = $client.SendAsync($msg, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                $status = [int]$resp.StatusCode
                if ($status -eq 200) {
                    $stream = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                    while ($null -ne ($line = $reader.ReadLine())) {
                        if ($line.StartsWith('event:answer')) {
                            $answers++
                            if ($ttfb -lt 0) { $ttfb = $sw.ElapsedMilliseconds }
                        } elseif ($line.StartsWith('event:done')) {
                            $done = 1
                        } elseif ($line.StartsWith('event:error')) {
                            $errEvt = 1
                        }
                    }
                    $reader.Close()
                }
            } catch {
                $evtErr = $_.Exception.GetBaseException().Message
                if ($evtErr.Length -gt 80) { $evtErr = $evtErr.Substring(0, 80) }
            }
            $lines += "$tag|i=$i|status=$status|ttfb=$ttfb|total=$($sw.ElapsedMilliseconds)|answers=$answers|done=$done|errEvt=$errEvt|ex=$evtErr"
        }
        $client.Dispose()
        return $lines
    } catch {
        return "$tag|JOB-ERROR: $($_.Exception.Message) @ $($_.InvocationInfo.PositionMessage)"
    }
}

$go = "$env:TEMP\w7d2-s3b-go.flag"
Remove-Item $go -ErrorAction SilentlyContinue
$jobs = @()
for ($t = 1; $t -le $Threads; $t++) {
    $jobs += Start-Job -ScriptBlock $jobScript -ArgumentList $BaseUrl, "$UserPrefix$t", $ReqsPerThread, $go
}
Start-Sleep -Milliseconds 500
New-Item -ItemType File -Path $go -Force | Out-Null
$null = Wait-Job -Job $jobs -Timeout 300
$all = @()
foreach ($j in $jobs) {
    $r = Receive-Job -Job $j -ErrorAction SilentlyContinue
    if ($r) { $all += @($r) }
    Remove-Job -Job $j -Force
}
$out += "--- per-request lines ---"
$out += $all

function Get-Pct([object[]]$vals, [double]$p) {
    $v = @($vals | Where-Object { $_ -ge 0 })
    if ($v.Count -eq 0) { return 'n/a' }
    $s = @($v | Sort-Object)
    $k = [Math]::Ceiling($p * $s.Count) - 1
    if ($k -lt 0) { $k = 0 }
    return [long]$s[$k]
}

$reqs = @($all | Where-Object { $_ -match '\|status=(\d+)\|ttfb=(-?\d+)\|total=(\d+)\|answers=(\d+)\|done=(\d)\|errEvt=(\d)' })
$ok200 = @($reqs | Where-Object { $_ -match '\|status=200\|' })
$doneCnt = @($reqs | Where-Object { $_ -match '\|done=1\|' }).Count
$errEvtCnt = @($reqs | Where-Object { $_ -match '\|errEvt=1\|' }).Count
$non200 = $reqs.Count - $ok200.Count
$ttfbVals = @($ok200 | ForEach-Object { if ($_ -match '\|ttfb=(-?\d+)\|') { [long]$Matches[1] } })
$totalVals = @($ok200 | ForEach-Object { if ($_ -match '\|total=(\d+)\|') { [long]$Matches[1] } })
$ansVals = @($ok200 | ForEach-Object { if ($_ -match '\|answers=(\d+)\|') { [long]$Matches[1] } })
$jobErrs = @($all | Where-Object { $_ -match 'JOB-ERROR' })

$out += "--- aggregate ---"
$out += "totalRequests=$($reqs.Count) status200=$($ok200.Count) non200=$non200 doneTail=$doneCnt errEvent=$errEvtCnt jobErrors=$($jobErrs.Count)"
$out += "ttfbMs P50=$(Get-Pct $ttfbVals 0.5) P95=$(Get-Pct $ttfbVals 0.95) max=$(Get-Pct $ttfbVals 1.0)  (first event:answer, gate-on 429 rows excluded)"
$out += "totalMs P50=$(Get-Pct $totalVals 0.5) P95=$(Get-Pct $totalVals 0.95) max=$(Get-Pct $totalVals 1.0)"
$out += "answerEvents P50=$(Get-Pct $ansVals 0.5) P95=$(Get-Pct $ansVals 0.95)"
$doneRate = 0
if ($ok200.Count -gt 0) { $doneRate = [Math]::Round(100.0 * $doneCnt / $ok200.Count, 1) }
$out += "doneTailRate=$doneRate% (of status200)"
$out += "end=$(Get-Date -Format 'HH:mm:ss.fff')"

if ($OutFile -ne '') {
    $out | Tee-Object -FilePath $OutFile
} else {
    $out
}
