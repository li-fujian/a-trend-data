import fetcher.BulkKLineFetcher;
import fetcher.StockUniverseFetcher;
import log.FetchLog;
import cache.KLineCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * 主入口：
 * 1. 拉取股票列表，写 config/stock-universe.json
 * 2. 拉上证 / 沪深300 / 中证500 / 科创50 / 创业板指，写 cache/kline/
 * 3. 批量拉个股K线，写 cache/kline/
 * 4. 个股完成后补抓一轮主要指数，尽量补齐当日日线
 * 5. 写日志 logs/fetch-log.json
 * 6. 打包并发布 GitHub Release latest（--no-push 跳过）
 *
 * 用法：
 *   mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
 *       "-Dexec.args=--repo-root /path/to/a-trend-data --mode incremental"
 *
 * 默认 repo-root 为当前工作目录的上一级（java/ 的父目录）。
 */
public class DataUpdateCli {

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int DEFAULT_INCREMENTAL_BARS = 420;
    private static final int DEFAULT_MAX_FAILED_TO_PUBLISH = 20;
    private static final int DEFAULT_MIN_FRESH_TO_PUBLISH = 1000;

    public static void main(String[] args) throws Exception {
        String repoRoot = resolveRepoRoot(args);
        String universeFile = repoRoot + "/config/stock-universe.json";
        String cacheDir     = repoRoot + "/cache/kline";
        String logFile      = repoRoot + "/logs/fetch-log.json";

        System.out.println("=== A-Trend Data Update ===");
        System.out.println("Repo root: " + repoRoot);

        String startedAt = LocalDateTime.now().format(DT_FMT);
        LocalDate runDate = LocalDate.now();
        String today = runDate.toString();
        boolean tradingDaysOnly = hasFlag(args, "--trading-days-only");
        if (tradingDaysOnly && !isWeekday(runDate)) {
            System.out.println("Today is not a weekday trading day candidate, skipped: " + today);
            return;
        }

        String mode = valueOf(args, "--mode", "incremental").toLowerCase();
        int incrementalBars = intValueOf(args, "--incremental-bars", DEFAULT_INCREMENTAL_BARS);
        int maxFailedToPublish = intValueOf(args, "--max-failed-to-publish", DEFAULT_MAX_FAILED_TO_PUBLISH);
        int minFreshToPublish = intValueOf(args, "--min-fresh-to-publish", DEFAULT_MIN_FRESH_TO_PUBLISH);
        int minSleepMs = intValueOf(args, "--min-sleep-ms", 1200);
        int maxSleepMs = intValueOf(args, "--max-sleep-ms", 2200);

        System.out.println("Mode: " + mode);
        if ("incremental".equals(mode)) {
            System.out.println("Incremental bars per symbol: " + incrementalBars);
        }
        System.out.println("Publish failure threshold: " + maxFailedToPublish);
        System.out.println("Publish fresh-symbol threshold: " + minFreshToPublish);

        // Step 1: 拉取股票列表
        System.out.println("\n[Step 1] Fetching stock universe from Sina (新浪)...");
        List<StockUniverseFetcher.StockEntry> stocks = StockUniverseFetcher.fetch();
        StockUniverseFetcher.save(stocks, universeFile);
        System.out.println("  -> " + stocks.size() + " stocks saved to " + universeFile);

        // Step 2: 主要指数（上证、沪深300、中证500、科创50、创业板指）
        System.out.println("\n[Step 2] Fetching daily index benchmarks...");
        FetchIndicesCli.fetchToCache(cacheDir);

        // Step 3: 批量拉个股K线（--only-sz 时只跑深市）
        boolean onlySz = hasFlag(args, "--only-sz");
        List<String> symbols = stocks.stream()
            .map(s -> s.symbol)
            .filter(s -> !onlySz || s.startsWith("sz"))
            .collect(Collectors.toList());
        if (onlySz) System.out.println("  [--only-sz] 只处理深市，共 " + symbols.size() + " 只");
        System.out.println("\n[Step 3] Fetching K-line data for " + symbols.size() + " symbols...");
        Function<String, List<cache.DailyBar>> stockFetcher = resolveStockFetcher(mode, incrementalBars);
        BulkKLineFetcher.FetchOptions options = BulkKLineFetcher.FetchOptions.defaults()
            .withSleepRange(minSleepMs, maxSleepMs);
        List<BulkKLineFetcher.FetchResult> results = BulkKLineFetcher.fetchAll(symbols, cacheDir, stockFetcher, options);
        BulkKLineFetcher.Summary summary = BulkKLineFetcher.buildSummary(results);

        System.out.printf("%n=== K-line fetch done: %d OK, %d skipped, %d failed ===%n",
            summary.ok, summary.skipped, summary.failed);
        if (!summary.failedSymbols.isEmpty()) {
            System.out.println("Failed: " + summary.failedSymbols);
        }
        int freshSymbols = countFreshSymbols(symbols, cacheDir);
        System.out.println("Fresh symbols today: " + freshSymbols);

        // Step 4: 个股批量拉取后再补抓一次指数，降低收盘后数据延迟导致的缺口
        System.out.println("\n[Step 4] Re-checking daily index benchmarks...");
        FetchIndicesCli.fetchToCache(cacheDir);

        // Step 5: 写日志
        System.out.println("\n[Step 5] Writing fetch log...");
        String finishedAt = LocalDateTime.now().format(DT_FMT);
        FetchLog.LogEntry logEntry = new FetchLog.LogEntry(
            today, startedAt, finishedAt,
            stocks.size(), summary.ok, summary.skipped, summary.failed,
            summary.failedSymbols
        );
        FetchLog.append(logFile, logEntry);
        System.out.println("  -> Log written to " + logFile);

        // Step 6: 发布 GitHub Release latest（可用 --no-push 跳过）
        boolean noPush = hasFlag(args, "--no-push");
        if (noPush) {
            System.out.println("\n[Step 6] Skipped (--no-push)");
        } else if (summary.failed > maxFailedToPublish) {
            System.out.println("\n[Step 6] Skipped: failed symbols (" + summary.failed
                + ") exceed --max-failed-to-publish=" + maxFailedToPublish);
            throw new RuntimeException("too many failed symbols, release was not updated");
        } else if (freshSymbols < minFreshToPublish) {
            System.out.println("\n[Step 6] Skipped: fresh symbols (" + freshSymbols
                + ") below --min-fresh-to-publish=" + minFreshToPublish);
            throw new RuntimeException("too few fresh symbols, release was not updated");
        } else {
            System.out.println("\n[Step 6] Publishing GitHub Release latest...");
            publishLatestRelease(repoRoot);
        }

        System.out.println("\n=== Done ===");
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) if (flag.equals(arg)) return true;
        return false;
    }

    private static String valueOf(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) return args[i + 1];
        }
        return defaultValue;
    }

    private static int intValueOf(String[] args, String key, int defaultValue) {
        String raw = valueOf(args, key, null);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw);
        }
    }

    private static Function<String, List<cache.DailyBar>> resolveStockFetcher(String mode, int incrementalBars) {
        if ("full".equals(mode)) {
            return monitor.trendfollowing.TencentQfqKLineFetcher::fetch;
        }
        if ("incremental".equals(mode)) {
            int bars = Math.max(1, incrementalBars);
            return symbol -> monitor.trendfollowing.TencentQfqKLineFetcher.fetch(symbol, bars);
        }
        throw new IllegalArgumentException("--mode must be incremental or full: " + mode);
    }

    private static boolean isWeekday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private static int countFreshSymbols(List<String> symbols, String cacheDir) {
        KLineCache cache = new KLineCache(cacheDir);
        int count = 0;
        for (String symbol : symbols) {
            try {
                if (cache.isFresh(symbol)) count++;
            } catch (Exception ignored) {
                // Treat invalid or unreadable symbols as not fresh.
            }
        }
        return count;
    }

    private static String resolveRepoRoot(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--repo-root".equals(args[i])) return args[i + 1];
        }
        // 默认：java/ 目录的父目录（即 a-trend-data/）
        try {
            return new File("..").getCanonicalPath();
        } catch (java.io.IOException e) {
            return new File("..").getAbsolutePath();
        }
    }

    /** Java 8 compatible replacement for InputStream.readAllBytes() (Java 9+). */
    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static void publishLatestRelease(String repoRoot) throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String scriptName = isWindows ? "publish-latest-release.ps1" : "publish-latest-release.sh";
        File script = new File(repoRoot, "scripts/" + scriptName);
        if (!script.isFile()) {
            throw new RuntimeException("Publish script not found: " + script.getAbsolutePath());
        }

        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.getAbsolutePath(),
                    "-RepoRoot", repoRoot
            );
        } else {
            pb = new ProcessBuilder("bash", script.getAbsolutePath(), "--repo-root", repoRoot);
        }
        pb.redirectErrorStream(true);
        pb.directory(new File(repoRoot));
        Process p = pb.start();

        String output = new String(readAllBytes(p.getInputStream()),
                java.nio.charset.StandardCharsets.UTF_8);
        int exit = p.waitFor();

        if (!output.isEmpty()) System.out.println(output.trim());
        if (exit != 0) {
            throw new RuntimeException("publish release failed (exit " + exit + ")");
        }
    }
}
