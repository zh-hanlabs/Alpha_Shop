# W5D2 RAG smoke: run knowledge-QA cases against /api/chat and save evidence.
# Script body is ASCII-only on purpose; Chinese case text lives in rag-cases.json (UTF-8),
# read with explicit UTF-8 to dodge PS5 ANSI mis-detection (README pitfall #6 family).
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'
$out = @()

$cases = [System.IO.File]::ReadAllText("$PSScriptRoot\rag-cases.json", [System.Text.Encoding]::UTF8) | ConvertFrom-Json

foreach ($c in $cases) {
    $bodyObj = @{ conversationId = 'rag-smoke-' + $c.id; userId = 'u1001'; message = $c.question }
    $json = $bodyObj | ConvertTo-Json
    $body = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/chat" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 180 -UseBasicParsing
        # README pitfall #6: charset-less response is Latin-1 decoded by PS5; take raw bytes, decode UTF-8
        $answer = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } catch {
        $answer = 'HTTP-FAIL: ' + $_.Exception.Message
    }
    $out += "=== [$($c.id)] $($c.question) ==="
    $out += $answer
    $out += ''
}

$out | Tee-Object -FilePath "$PSScriptRoot\smoke-w5d2.txt"
