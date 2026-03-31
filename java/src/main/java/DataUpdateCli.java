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
 * 2. 批量拉K线，写 cache/kline/
 * 3. 写日志 logs/fetch-log.json
 * 4. git add + commit + push
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

        // Step 2: 批量拉K线
        List<String> symbols = stocks.stream()
            .map(s -> s.symbol)
            .collect(Collectors.toList());
        symbols = symbols.subList(0, Math.min(1, symbols.size()));
        System.out.println("\n[Step 2] Fetching K-line data for " + symbols.size() + " symbols...");
        List<BulkKLineFetcher.FetchResult> results = BulkKLineFetcher.fetchAll(symbols, cacheDir);
        BulkKLineFetcher.Summary summary = BulkKLineFetcher.buildSummary(results);

        System.out.printf("%n=== K-line fetch done: %d OK, %d skipped, %d failed ===%n",
            summary.ok, summary.skipped, summary.failed);
        if (!summary.failedSymbols.isEmpty()) {
            System.out.println("Failed: " + summary.failedSymbols);
        }

        // Step 3: 写日志
        System.out.println("\n[Step 3] Writing fetch log...");
        String finishedAt = LocalDateTime.now().format(DT_FMT);
        FetchLog.LogEntry logEntry = new FetchLog.LogEntry(
            today, startedAt, finishedAt,
            stocks.size(), summary.ok, summary.skipped, summary.failed,
            summary.failedSymbols
        );
        FetchLog.append(logFile, logEntry);
        System.out.println("  -> Log written to " + logFile);

        // Step 4: git push
        System.out.println("\n[Step 4] Git commit and push...");
        gitPush(repoRoot, today);

        System.out.println("\n=== Done ===");
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

    private static void gitPush(String repoRoot, String date) throws Exception {
        String[][] commands = {
            {"git", "-C", repoRoot, "add", "."},
            {"git", "-C", repoRoot, "commit", "-m", "data: " + date},
            {"git", "-C", repoRoot, "push"}
        };
        boolean[] allowNonZero = {false, true, false}; // commit 允许非零（nothing to commit）

        for (int i = 0; i < commands.length; i++) {
            String cmdStr = String.join(" ", commands[i]);
            System.out.println("  $ " + cmdStr);

            ProcessBuilder pb = new ProcessBuilder(commands[i]);
            pb.redirectErrorStream(true); // 合并 stderr 到 stdout，避免缓冲区死锁
            Process p = pb.start();

            // 先读输出，再等待进程退出，避免管道缓冲区满导致死锁
            String output = new String(readAllBytes(p.getInputStream()),
                java.nio.charset.StandardCharsets.UTF_8);
            int exit = p.waitFor();

            if (!output.isEmpty()) System.out.println(output.trim());

            if (exit != 0 && !allowNonZero[i]) {
                throw new RuntimeException("git command failed (exit " + exit + "): " + cmdStr);
            }
        }
    }
}
