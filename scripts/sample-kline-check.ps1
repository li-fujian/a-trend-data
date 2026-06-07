# Sample K-line cache integrity check (qfq migration)
param(
    [string]$CacheDir = "E:\code\a-trend-data\cache\kline",
    [string]$UniverseFile = "E:\code\a-trend-data\config\stock-universe.json"
)

$ErrorActionPreference = "Stop"
$now = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

function Test-KlineFile {
    param([string]$Path)
    $issues = @()
    try {
        $raw = Get-Content -Path $Path -Raw -Encoding UTF8
        $j = $raw | ConvertFrom-Json
    } catch {
        return @{ symbol = (Split-Path $Path -Leaf); ok = $false; issues = @("JSON parse error: $_") }
    }

    $sym = $j.symbol
    if ($j.adjustment -ne "qfq") { $issues += "adjustment!=qfq ($($j.adjustment))" }
    if (-not $j.bars -or $j.bars.Count -eq 0) { $issues += "empty bars" }

    if ($j.bars -and $j.bars.Count -gt 0) {
        $dates = @($j.bars | ForEach-Object { $_.date } | Where-Object { $_ })
        $cnt = $dates.Count
        if ($cnt -eq 0) { $issues += "no dated bars" }
        else {
            $first = $dates[0]
            $last = $dates[$cnt - 1]
            $maxDate = ($dates | Sort-Object)[-1]
            $minDate = ($dates | Sort-Object)[0]

            # sort check (compare file order vs sorted)
            $sorted = $true
            for ($i = 1; $i -lt $cnt; $i++) {
                if ($dates[$i] -lt $dates[$i - 1]) { $sorted = $false; break }
            }
            if (-not $sorted) { $issues += "dates not ascending (file: $first..$last)" }

            if ($j.last_updated -ne $maxDate) {
                $issues += "last_updated=$($j.last_updated) max=$maxDate"
            }

            $neg = 0
            foreach ($b in $j.bars) {
                foreach ($f in @("open", "high", "low", "close")) {
                    $v = $b.$f
                    if ($null -ne $v -and [double]$v -le 0) { $neg++ }
                }
            }
            if ($neg -gt 0) { $issues += "non-positive OHLC fields: $neg" }

            return @{
                symbol = $sym
                ok = ($issues.Count -eq 0)
                issues = $issues
                bars = $cnt
                range = "$minDate..$maxDate"
                last_updated = $j.last_updated
            }
        }
    }

    return @{ symbol = $sym; ok = ($issues.Count -eq 0); issues = $issues; bars = 0 }
}

# --- coverage (fast: file count + qfq grep + recent mtime) ---
$allFiles = Get-ChildItem -Path $CacheDir -Filter "*.json" -File
$totalFiles = $allFiles.Count
$qfqCount = 0
$qfqHits = findstr /m /c:"\"adjustment\": \"qfq\"" (Join-Path $CacheDir "*.json") 2>$null
if ($qfqHits) { $qfqCount = @($qfqHits).Count }
$noAdjCount = $totalFiles - $qfqCount
$recentMtime = $allFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 5

$universeCount = 0
if (Test-Path $UniverseFile) {
    $u = Get-Content $UniverseFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $universeCount = $u.count
}

# --- fixed samples ---
$fixedSamples = @(
    "sh000001", "sh000300", "sh000905", "sh000688", "sz399006",
    "sh600519", "sh600036", "sz000001", "sz000858", "sh601318"
)

# --- recently written samples ---
$recentSamples = $recentMtime | ForEach-Object { $_.BaseName }

$toCheck = @($fixedSamples + $recentSamples | Select-Object -Unique)
$results = @()
$failCount = 0
foreach ($s in $toCheck) {
    $p = Join-Path $CacheDir "$s.json"
    if (-not (Test-Path $p)) {
        $results += @{ symbol = $s; ok = $false; issues = @("file missing"); bars = 0 }
        $failCount++
        continue
    }
    $r = Test-KlineFile -Path $p
    $results += $r
    if (-not $r.ok) { $failCount++ }
}

Write-Output "=== KLINE SAMPLE CHECK $now ==="
Write-Output "cache_files=$totalFiles universe=$universeCount qfq=$qfqCount no_or_bad_adj=$noAdjCount"
Write-Output "sampled=$($toCheck.Count) fail=$failCount"
foreach ($r in $results) {
    if ($r.ok) {
        Write-Output ("OK   {0,-12} bars={1,5} range={2} last_updated={3}" -f $r.symbol, $r.bars, $r.range, $r.last_updated)
    } else {
        Write-Output ("FAIL {0,-12} {1}" -f $r.symbol, ($r.issues -join "; "))
    }
}
Write-Output "=== END ==="
