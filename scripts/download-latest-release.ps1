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

Require-Command gh
Require-Command zstd
Require-Command tar

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

Write-Output "==> Downloading ${ReleaseTag}/${AssetName}"
gh release download $ReleaseTag `
    --pattern $AssetName `
    --dir $DistDir `
    --clobber

Write-Output "==> Verifying archive"
zstd -q -t $ArchivePath
if ($LASTEXITCODE -ne 0) {
    throw "Archive verification failed: $ArchivePath"
}

Write-Output "==> Extracting to $RepoRoot"
zstd -d -c $ArchivePath | tar -xf - -C $RepoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Extract failed"
}

Write-Output "==> Done"
