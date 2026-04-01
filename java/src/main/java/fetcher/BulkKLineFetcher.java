package fetcher;

import cache.DailyBar;
import cache.KLineCache;
import monitor.trendfollowing.SinaKLineFetcher;

import java.util.*;
import java.util.function.Function;

/**
 * 批量拉取K线，限速+重试+跳过fresh。
 * 每次请求后 sleep 2200-3800ms（随机），每50只额外 sleep 5s。
 * 失败重试2次（指数退避），全部完成后对失败列表补偿重跑一次。
 * 连续失败达到阈值时，执行额外冷却，降低被上游限流的概率。
 */
public class BulkKLineFetcher {

    private static final int BATCH_SIZE = 50;
    private static final int BATCH_PAUSE_MS = 5000;
    private static final int MIN_SLEEP_MS = 2200;
    private static final int MAX_SLEEP_MS = 3800;
    private static final int RETRY_COUNT = 2;
    private static final int RETRY_BASE_SLEEP_MS = 6000;
    private static final int FAILURE_STREAK_COOLDOWN_THRESHOLD = 5;
    private static final int FAILURE_STREAK_COOLDOWN_MS = 45000;

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
        Map<String, Integer> symbolIndex = new HashMap<>();
        List<String> failed = new ArrayList<>();
        int failureStreak = 0;
        int total = symbols.size();
        Random rand = new Random();

        for (int i = 0; i < total; i++) {
            String symbol = symbols.get(i);
            FetchResult result = fetchWithRetry(symbol, cacheDir, fetcher, RETRY_COUNT);
            results.add(result);
            symbolIndex.put(symbol, i);
            if (result.status == Status.FAILED) {
                failed.add(symbol);
                failureStreak++;
            } else {
                failureStreak = 0;
            }

            System.out.printf("[%4d/%d] %-12s %s%n", i + 1, total, symbol, result.status);

            // 批间停顿
            if ((i + 1) % BATCH_SIZE == 0 && i + 1 < total) {
                System.out.println("--- batch pause " + BATCH_PAUSE_MS + "ms ---");
                sleep(BATCH_PAUSE_MS);
            } else if (result.status != Status.SKIPPED) {
                sleep(MIN_SLEEP_MS + rand.nextInt(MAX_SLEEP_MS - MIN_SLEEP_MS + 1));
            }

            if (failureStreak >= FAILURE_STREAK_COOLDOWN_THRESHOLD) {
                System.out.println("--- failure streak cooldown " + FAILURE_STREAK_COOLDOWN_MS + "ms ---");
                sleep(FAILURE_STREAK_COOLDOWN_MS);
                failureStreak = 0;
            }
        }

        // 补偿重跑失败列表
        if (!failed.isEmpty()) {
            System.out.println("\n=== 补偿重跑 " + failed.size() + " 只失败标的 ===");
            sleep(BATCH_PAUSE_MS);
            for (String symbol : failed) {
                FetchResult retry = fetchWithRetry(symbol, cacheDir, fetcher, RETRY_COUNT);
                // 更新 results 中对应的条目
                Integer idx = symbolIndex.get(symbol);
                if (idx != null) results.set(idx, retry);
                System.out.printf("  补偿 %-12s %s%n", symbol, retry.status);
                sleep(MIN_SLEEP_MS + rand.nextInt(MAX_SLEEP_MS - MIN_SLEEP_MS + 1));
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
            if (attempt < maxRetry) {
                int backoff = RETRY_BASE_SLEEP_MS * (attempt + 1);
                sleep(backoff);
            }
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
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
