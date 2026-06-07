import fetcher.BulkKLineFetcher;
import fetcher.StockUniverseFetcher;
import log.FetchLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

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
 *       "-Dexec.args=--repo-root /path/to/a-trend-data"
 *
 * 默认 repo-root 为当前工作目录的上一级（java/ 的父目录）。
 */
public class DataUpdateCli {

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static void main(String[] args) throws Exception {
        String repoRoot = resolveRepoRoot(args);
        String universeFile = repoRoot + "/config/stock-universe.json";
        String cacheDir     = repoRoot + "/cache/kline";
        String logFile      = repoRoot + "/logs/fetch-log.json";

        System.out.println("=== A-Trend Data Update ===");
        System.out.println("Repo root: " + repoRoot);

        String startedAt = LocalDateTime.now().format(DT_FMT);
        String today = java.time.LocalDate.now().toString();

        // Step 1: 拉取股票列表
        System.out.println("\n[Step 1] Fetching stock universe from eastmoney...");
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
        List<BulkKLineFetcher.FetchResult> results = BulkKLineFetcher.fetchAll(symbols, cacheDir);
        BulkKLineFetcher.Summary summary = BulkKLineFetcher.buildSummary(results);

        System.out.printf("%n=== K-line fetch done: %d OK, %d skipped, %d failed ===%n",
            summary.ok, summary.skipped, summary.failed);
        if (!summary.failedSymbols.isEmpty()) {
            System.out.println("Failed: " + summary.failedSymbols);
        }

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
