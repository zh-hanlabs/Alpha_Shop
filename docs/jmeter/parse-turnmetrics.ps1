param(
    [Parameter(Mandatory = $true)][string]$LogPath,
    [string]$Label = 'turnmetrics',
    [string]$OutFile = ''
)

# W7D2 T2.2 TurnMetrics single-line-JSON log parser (server-side data source #2 of the
# dual-source reconciliation; ring buffer is only 100 turns so the log FILE is the source
# of truth under load - W6D4 design). Parses every log line emitted by
# com.shopagent.infra.obs.TurnMetricsRecorder (logger may be abbreviated as c.s.i.o.TurnMetricsRecorder
# in the default Spring Boot pattern), extracts the JSON payload, aggregates:
#   outcome distribution / totalMs+llmMs+firstTokenMs percentiles (OK-only) / toolCalls
#   distribution / token sums + usageHits coverage.
# Conventions from W6 PS5 pits: UTF-8 BOM file, ASCII-only literals.
$ErrorActionPreference = 'Stop'
$out = @()
$out += "=== TurnMetrics parse: $Label ==="

if (-not (Test-Path $LogPath)) {
    $out += "ERROR: log file not found: $LogPath"
    if ($OutFile -ne '') { $out | Tee-Object -FilePath $OutFile } else { $out }
    exit 1
}

$turns = @()
$scanLines = 0
# logback holds the file while the app runs: open with FileShare.ReadWrite so the parser
# works against a LIVE log too (D3 picks numbers mid-run)
$fs = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$reader = New-Object System.IO.StreamReader($fs)
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        $scanLines++
        $idx = $line.IndexOf('TurnMetricsRecorder')
        if ($idx -lt 0) { continue }
        $b = $line.IndexOf('{', $idx)
        if ($b -lt 0) { continue }
        $json = $line.Substring($b)
        $o = $null
        try { $o = $json | ConvertFrom-Json } catch { continue }
        if ($null -ne $o -and $null -ne $o.PSObject.Properties['outcome']) { $turns += $o }
    }
} finally {
    $reader.Close()
}

$out += "log=$LogPath scanLines=$scanLines metricLines=$($turns.Count)"

function Get-Pct([object[]]$vals, [double]$p) {
    if ($vals.Count -eq 0) { return 'n/a' }
    $s = @($vals | Sort-Object)
    $k = [Math]::Ceiling($p * $s.Count) - 1
    if ($k -lt 0) { $k = 0 }
    return [long]$s[$k]
}

if ($turns.Count -eq 0) {
    $out += "no TurnMetrics lines parsed"
    if ($OutFile -ne '') { $out | Tee-Object -FilePath $OutFile } else { $out }
    exit 1
}

$byOutcome = @{}
foreach ($t in $turns) {
    $k = [string]$t.outcome
    if (-not $byOutcome.ContainsKey($k)) { $byOutcome[$k] = 0 }
    $byOutcome[$k] = $byOutcome[$k] + 1
}
$out += "--- outcome distribution ---"
foreach ($k in ($byOutcome.Keys | Sort-Object)) { $out += "outcome=$k count=$($byOutcome[$k])" }

$ok = @($turns | Where-Object { [string]$_.outcome -eq 'OK' })
$out += "--- percentiles (OK-only, n=$($ok.Count)) ---"
$totalVals = @($ok | ForEach-Object { [long]$_.totalMs })
$llmVals = @($ok | ForEach-Object { [long]$_.llmMs })
$ftVals = @($ok | Where-Object { $null -ne $_.firstTokenMs } | ForEach-Object { [long]$_.firstTokenMs })
$out += "totalMs     P50=$(Get-Pct $totalVals 0.5) P95=$(Get-Pct $totalVals 0.95) max=$(Get-Pct $totalVals 1.0)"
$out += "llmMs       P50=$(Get-Pct $llmVals 0.5) P95=$(Get-Pct $llmVals 0.95) max=$(Get-Pct $llmVals 1.0)"
$out += "firstTokenMs P50=$(Get-Pct $ftVals 0.5) P95=$(Get-Pct $ftVals 0.95) max=$(Get-Pct $ftVals 1.0) (hits=$($ftVals.Count))"

$out += "--- tokens ---"
$usageHits = @($turns | Where-Object { $null -ne $_.promptTokens }).Count
$pSum = 0; $cSum = 0
foreach ($t in $turns) {
    if ($null -ne $t.promptTokens) { $pSum += [long]$t.promptTokens }
    if ($null -ne $t.completionTokens) { $cSum += [long]$t.completionTokens }
}
$aSum = 0
foreach ($t in $turns) { if ($null -ne $t.answerChars) { $aSum += [int]$t.answerChars } }
$out += "promptTokens=$pSum completionTokens=$cSum usageHits=$usageHits/$($turns.Count) answerCharsSum=$aSum (stub model: usage null = N/A, answerChars proxy)"

$out += "--- toolCalls distribution ---"
$tools = @{}
foreach ($t in $turns) {
    if ($null -eq $t.toolCalls) { continue }
    foreach ($p in $t.toolCalls.PSObject.Properties) {
        if (-not $tools.ContainsKey($p.Name)) { $tools[$p.Name] = 0 }
        $tools[$p.Name] = $tools[$p.Name] + [int]$p.Value
    }
}
if ($tools.Count -eq 0) {
    $out += "(none - stub model answers without tool calls on this run)"
} else {
    foreach ($k in ($tools.Keys | Sort-Object)) { $out += "tool=$k count=$($tools[$k])" }
}

$out += "=== end parse: $Label ==="

if ($OutFile -ne '') {
    $out | Tee-Object -FilePath $OutFile
} else {
    $out
}
