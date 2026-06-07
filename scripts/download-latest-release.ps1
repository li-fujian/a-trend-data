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

Write-Output "==> Extracting to $RepoRoot"
zstd -d -c $ArchivePath | tar -xf - -C $RepoRoot

Write-Output "==> Done"
