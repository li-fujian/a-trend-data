param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"

$AssetName = "kline-latest.tar.zst"
$ReleaseTag = "latest"
$DistDir = Join-Path $RepoRoot "dist"
$ArchivePath = Join-Path $DistDir $AssetName

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing required command: $Name"
    }
}

Require-Command tar
Require-Command zstd
Require-Command gh

$KlineDir = Join-Path $RepoRoot "cache\kline"
if (-not (Test-Path $KlineDir)) {
    throw "cache/kline not found under $RepoRoot"
}

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

Write-Output "==> Packaging $ArchivePath"
Remove-Item -Force -ErrorAction SilentlyContinue $ArchivePath
Push-Location $RepoRoot
try {
    tar -cf - cache/kline config/stock-universe.json logs/fetch-log.json | zstd -T0 -19 -o $ArchivePath
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw "Packaging failed"
}

Write-Output "==> Verifying archive"
zstd -q -t $ArchivePath
if ($LASTEXITCODE -ne 0) {
    throw "Archive verification failed: $ArchivePath"
}

$Repo = gh repo view --json nameWithOwner -q .nameWithOwner
$ReleaseExists = $false
try {
    gh release view $ReleaseTag --repo $Repo | Out-Null
    $ReleaseExists = $true
} catch {
    $ReleaseExists = $false
}

if (-not $ReleaseExists) {
    Write-Output "==> Creating release $ReleaseTag"
    gh release create $ReleaseTag `
        --title "Latest K-line snapshot" `
        --notes "Rolling latest A-share qfq K-line bundle. Updated $(Get-Date -Format 'yyyy-MM-dd')."
}

Write-Output "==> Uploading asset (clobber)"
gh release upload $ReleaseTag $ArchivePath --clobber

Write-Output "==> Pruning old releases (keep only $ReleaseTag)"
$OldTags = gh release list --limit 200 --json tagName -q '.[].tagName'
foreach ($Tag in $OldTags) {
    if ($Tag -ne $ReleaseTag) {
        Write-Output "    delete $Tag"
        gh release delete $Tag --yes --cleanup-tag
    }
}

Write-Output "==> Done: ${ReleaseTag}/${AssetName}"
