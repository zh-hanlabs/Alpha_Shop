param([string]$Branch = "")

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$prevEnc = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    if (-not $Branch) { $Branch = (git rev-parse --abbrev-ref HEAD).Trim() }
    $upstream = "origin/$Branch"

    $before = $null
    git rev-parse --verify --quiet $upstream | Out-Null
    if ($LASTEXITCODE -eq 0) { $before = (git rev-parse $upstream).Trim() }

    $range = if ($before) { "$upstream..HEAD" } else { "HEAD" }
    $commits = @(git log --pretty=format:"%h %s" $range | Where-Object { $_ })

    if ($commits.Count -eq 0) {
        Write-Host "没有待推送的提交，无需推送。"
        return
    }

    if (git status --porcelain) { Write-Warning "工作区有未提交改动，本次推送不包含它们。" }

    git push -u origin $Branch
    if ($LASTEXITCODE -ne 0) { throw "git push 失败，未写入记录" }

    $after = (git rev-parse $upstream).Trim()
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
    $rangeText = if ($before) { "$($before.Substring(0,7))..$($after.Substring(0,7))" } else { "新分支 -> $($after.Substring(0,7))" }

    $entry = "## $stamp  $Branch -> origin/$Branch  [$rangeText]"
    $entry += "`n" + (($commits | ForEach-Object { "- $_" }) -join "`n") + "`n"
    Add-Content -Path (Join-Path $repoRoot "PUSH_LOG.md") -Value $entry -Encoding UTF8

    Write-Host "已推送 $($commits.Count) 个提交，记录已写入 PUSH_LOG.md"
} finally {
    [Console]::OutputEncoding = $prevEnc
}
