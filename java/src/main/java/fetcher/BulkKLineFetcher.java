package fetcher;

import cache.DailyBar;
import cache.KLineCache;
import monitor.trendfollowing.SinaKLineFetcher;

import java.util.*;
import java.util.function.Function;

/**
 * 批量拉取K线，限速+重试+跳过fresh。
 * 每次请求后 sleep 300-500ms（随机），每50只额外 sleep 2s。
 * 失败重试2次，全部完成后对失败列表补偿重跑一次。
 */
public class BulkKLineFetcher {

    private static final int BATCH_SIZE = 50;
    private static final int BATCH_PAUSE_MS = 2000;
    private static final int MIN_SLEEP_MS = 300;
    private static final int MAX_SLEEP_MS = 500;
    private static final int RETRY_COUNT = 2;
    private static final int RETRY_SLEEP_MS = 1000;

    public enum Status { OK, SKIPPED, FAILED }

    public static class FetchResult {
        public final String symbol;
        public final Status status;

        public FetchResult(String symbol, Status status) {
            this.symbol = symbol;
            this.status = status;
        }
    }

    public static class Summary {
        public int ok;
        public int skipped;
        public int failed;
        public List<String> failedSymbols;
    }

    /**
     * 拉取单只股票。isFresh → SKIPPED；fetch返回空 → FAILED；否则写缓存 → OK。
     */
    public static FetchResult fetchOne(String symbol, String cacheDir,
                                        Function<String, List<DailyBar>> fetcher) {
        KLineCache cache = new KLineCache(cacheDir);
        if (cache.isFresh(symbol)) {
            return new FetchResult(symbol, Status.SKIPPED);
        }
        List<DailyBar> bars = fetcher.apply(symbol);
        if (bars == null || bars.isEmpty()) {
            return new FetchResult(symbol, Status.FAILED);
        }
        cache.refresh(symbol, s -> bars);
        return new FetchResult(symbol, Status.OK);
    }

    /**
     * 批量拉取所有股票，带限速、重试、补偿重跑。使用真实 SinaKLineFetcher。
     */
    public static List<FetchResult> fetchAll(List<String> symbols, String cacheDir) {
        return fetchAll(symbols, cacheDir, SinaKLineFetcher::fetch);
    }

    /**
     * 可注入 fetcher 的版本（测试用）。
     */
    public static List<FetchResult> fetchAll(List<String> symbols, String cacheDir,
                                              Function<String, List<DailyBar>> fetcher) {
        List<FetchResult> results = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int total = symbols.size();
        Random rand = new Random();

        for (int i = 0; i < total; i++) {
            String symbol = symbols.get(i);
            FetchResult result = fetchWithRetry(symbol, cacheDir, fetcher, RETRY_COUNT);
            results.add(result);
            if (result.status == Status.FAILED) {
                failed.add(symbol);
            }

            System.out.printf("[%4d/%d] %-12s %s%n", i + 1, total, symbol, result.status);

            // 批间停顿
            if ((i + 1) % BATCH_SIZE == 0 && i + 1 < total) {
                System.out.println("--- batch pause " + BATCH_PAUSE_MS + "ms ---");
                sleep(BATCH_PAUSE_MS);
            } else if (result.status != Status.SKIPPED) {
                // 单次请求间隔：300-500ms 随机
                sleep(MIN_SLEEP_MS + rand.nextInt(MAX_SLEEP_MS - MIN_SLEEP_MS + 1));
            }
        }

        // 补偿重跑失败列表
        if (!failed.isEmpty()) {
            System.out.println("\n=== 补偿重跑 " + failed.size() + " 只失败标的 ===");
            sleep(BATCH_PAUSE_MS);
            Random rand2 = new Random();
            for (String symbol : failed) {
                FetchResult retry = fetchWithRetry(symbol, cacheDir, fetcher, RETRY_COUNT);
                // 更新 results 中对应的条目
                for (int i = 0; i < results.size(); i++) {
                    if (results.get(i).symbol.equals(symbol)) {
                        results.set(i, retry);
                        break;
                    }
                }
                System.out.printf("  补偿 %-12s %s%n", symbol, retry.status);
                sleep(MIN_SLEEP_MS + rand2.nextInt(MAX_SLEEP_MS - MIN_SLEEP_MS + 1));
            }
        }

        return results;
    }

    private static FetchResult fetchWithRetry(String symbol, String cacheDir,
                                               Function<String, List<DailyBar>> fetcher,
                                               int maxRetry) {
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            FetchResult r = fetchOne(symbol, cacheDir, fetcher);
            if (r.status != Status.FAILED) return r;
            if (attempt < maxRetry) sleep(RETRY_SLEEP_MS);
        }
        return new FetchResult(symbol, Status.FAILED);
    }

    public static Summary buildSummary(List<FetchResult> results) {
        Summary s = new Summary();
        s.failedSymbols = new ArrayList<>();
        for (FetchResult r : results) {
            if (r.status == Status.OK) s.ok++;
            else if (r.status == Status.SKIPPED) s.skipped++;
            else { s.failed++; s.failedSymbols.add(r.symbol); }
        }
        return s;
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
