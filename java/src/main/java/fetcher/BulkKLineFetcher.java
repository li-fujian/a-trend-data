package fetcher;

import cache.DailyBar;
import cache.KLineCache;
import monitor.trendfollowing.TencentQfqKLineFetcher;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 批量拉取K线，限速+重试+跳过fresh。
 * 每次请求后 sleep 2200-3800ms（随机），每50只额外 sleep 5s。
 * 失败重试2次（指数退避），全部完成后对失败列表补偿重跑一次。
 * 连续失败达到阈值时，执行额外冷却，降低被上游限流的概率。
 */
public class BulkKLineFetcher {

    private static final int BATCH_SIZE = 50;
    private static final int BATCH_PAUSE_MS = 3000;
    private static final int MIN_SLEEP_MS = 1200;
    private static final int MAX_SLEEP_MS = 2200;
    private static final int RETRY_COUNT = 2;
    private static final int RETRY_BASE_SLEEP_MS = 6000;
    private static final int FAILURE_STREAK_COOLDOWN_THRESHOLD = 5;
    private static final int FAILURE_STREAK_COOLDOWN_MS = 45000;
    private static final int INCREMENTAL_DATALEN = 30;
    private static final int WORKERS = 1;

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

    public static class FetchOptions {
        public int batchSize = BATCH_SIZE;
        public int batchPauseMs = BATCH_PAUSE_MS;
        public int minSleepMs = MIN_SLEEP_MS;
        public int maxSleepMs = MAX_SLEEP_MS;
        public int retryCount = RETRY_COUNT;
        public int retryBaseSleepMs = RETRY_BASE_SLEEP_MS;
        public int failureStreakCooldownThreshold = FAILURE_STREAK_COOLDOWN_THRESHOLD;
        public int failureStreakCooldownMs = FAILURE_STREAK_COOLDOWN_MS;
        public int workers = WORKERS;

        public static FetchOptions defaults() {
            return new FetchOptions();
        }

        public FetchOptions withSleepRange(int minMs, int maxMs) {
            this.minSleepMs = Math.max(0, minMs);
            this.maxSleepMs = Math.max(this.minSleepMs, maxMs);
            return this;
        }

        public FetchOptions withWorkers(int workers) {
            this.workers = Math.max(1, workers);
            return this;
        }
    }

    /**
     * 拉取单只股票。isFresh → SKIPPED；fetch返回空 → FAILED；否则写缓存 → OK。
     */
    public static FetchResult fetchOne(String symbol, String cacheDir,
                                        Function<String, List<DailyBar>> fetcher) {
        return fetchOne(symbol, cacheDir, fetcher, fetcher);
    }

    /**
     * 拉取单只股票。已有 qfq 缓存时走增量 fetcher；缺缓存/旧口径时走全量 fetcher。
     */
    public static FetchResult fetchOne(String symbol, String cacheDir,
                                        Function<String, List<DailyBar>> fullFetcher,
                                        Function<String, List<DailyBar>> incrementalFetcher) {
        KLineCache cache = new KLineCache(cacheDir);
        if (cache.isFresh(symbol)) {
            return new FetchResult(symbol, Status.SKIPPED);
        }
        boolean canIncremental = cache.hasQfqAdjustment(symbol) && !cache.load(symbol).isEmpty();
        Function<String, List<DailyBar>> selectedFetcher =
            canIncremental ? incrementalFetcher : fullFetcher;
        List<DailyBar> bars = selectedFetcher.apply(symbol);
        if (bars == null || bars.isEmpty()) {
            return new FetchResult(symbol, Status.FAILED);
        }
        cache.refresh(symbol, s -> bars);
        return new FetchResult(symbol, Status.OK);
    }

    /**
     * 批量拉取所有股票，带限速、重试、补偿重跑。使用腾讯财经前复权日线。
     */
    public static List<FetchResult> fetchAll(List<String> symbols, String cacheDir) {
        return fetchAll(
            symbols,
            cacheDir,
            symbol -> TencentQfqKLineFetcher.fetch(symbol, TencentQfqKLineFetcher.MAX_DATALEN),
            symbol -> TencentQfqKLineFetcher.fetch(symbol, INCREMENTAL_DATALEN),
            FetchOptions.defaults()
        );
    }

    /**
     * 可注入 fetcher 的版本（测试用）。
     */
    public static List<FetchResult> fetchAll(List<String> symbols, String cacheDir,
                                              Function<String, List<DailyBar>> fetcher) {
        return fetchAll(symbols, cacheDir, fetcher, FetchOptions.defaults());
    }

    public static List<FetchResult> fetchAll(List<String> symbols, String cacheDir,
                                              Function<String, List<DailyBar>> fetcher,
                                              FetchOptions options) {
        return fetchAll(symbols, cacheDir, fetcher, fetcher, options);
    }

    public static List<FetchResult> fetchAll(List<String> symbols, String cacheDir,
                                              Function<String, List<DailyBar>> fullFetcher,
                                              Function<String, List<DailyBar>> incrementalFetcher,
                                              FetchOptions options) {
        if (options == null) {
            options = FetchOptions.defaults();
        }
        normalizeOptions(options);

        if (options.workers > 1 && symbols.size() > 1) {
            return fetchAllParallel(symbols, cacheDir, fullFetcher, incrementalFetcher, options);
        }

        List<FetchResult> results = new ArrayList<>();
        Map<String, Integer> symbolIndex = new HashMap<>();
        List<String> failed = new ArrayList<>();
        int failureStreak = 0;
        int total = symbols.size();
        Random rand = new Random();

        for (int i = 0; i < total; i++) {
            String symbol = symbols.get(i);
            FetchResult result = fetchWithRetry(
                symbol,
                cacheDir,
                fullFetcher,
                incrementalFetcher,
                options.retryCount,
                options.retryBaseSleepMs
            );
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
            if ((i + 1) % options.batchSize == 0 && i + 1 < total) {
                System.out.println("--- batch pause " + options.batchPauseMs + "ms ---");
                sleep(options.batchPauseMs);
            } else if (result.status != Status.SKIPPED) {
                sleep(randomSleepMs(rand, options));
            }

            if (failureStreak >= options.failureStreakCooldownThreshold) {
                System.out.println("--- failure streak cooldown " + options.failureStreakCooldownMs + "ms ---");
                sleep(options.failureStreakCooldownMs);
                failureStreak = 0;
            }
        }

        // 补偿重跑失败列表
        if (!failed.isEmpty()) {
            System.out.println("\n=== 补偿重跑 " + failed.size() + " 只失败标的 ===");
            sleep(options.batchPauseMs);
            for (String symbol : failed) {
                FetchResult retry = fetchWithRetry(
                    symbol,
                    cacheDir,
                    fullFetcher,
                    incrementalFetcher,
                    options.retryCount,
                    options.retryBaseSleepMs
                );
                // 更新 results 中对应的条目
                Integer idx = symbolIndex.get(symbol);
                if (idx != null) results.set(idx, retry);
                System.out.printf("  补偿 %-12s %s%n", symbol, retry.status);
                sleep(randomSleepMs(rand, options));
            }
        }

        return results;
    }

    private static List<FetchResult> fetchAllParallel(List<String> symbols, String cacheDir,
                                                       Function<String, List<DailyBar>> fullFetcher,
                                                       Function<String, List<DailyBar>> incrementalFetcher,
                                                       FetchOptions options) {
        int total = symbols.size();
        int workers = Math.min(options.workers, total);
        FetchResult[] results = new FetchResult[total];
        List<Integer> failedIndexes = Collections.synchronizedList(new ArrayList<Integer>());
        AtomicInteger done = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();

        System.out.println("Parallel workers: " + workers);

        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    Random rand = new Random(System.nanoTime() + workerId);
                    int processedByWorker = 0;
                    int failureStreak = 0;

                    for (int i = workerId; i < total; i += workers) {
                        String symbol = symbols.get(i);
                        FetchResult result = fetchWithRetry(
                            symbol,
                            cacheDir,
                            fullFetcher,
                            incrementalFetcher,
                            options.retryCount,
                            options.retryBaseSleepMs
                        );
                        results[i] = result;
                        processedByWorker++;

                        if (result.status == Status.FAILED) {
                            failedIndexes.add(i);
                            failureStreak++;
                        } else {
                            failureStreak = 0;
                        }

                        int current = done.incrementAndGet();
                        synchronized (System.out) {
                            System.out.printf("[%4d/%d] [w%d] %-12s %s%n",
                                current, total, workerId + 1, symbol, result.status);
                        }

                        if (processedByWorker % options.batchSize == 0 && current < total) {
                            synchronized (System.out) {
                                System.out.println("--- worker " + (workerId + 1)
                                    + " batch pause " + options.batchPauseMs + "ms ---");
                            }
                            sleep(options.batchPauseMs);
                        } else if (result.status != Status.SKIPPED) {
                            sleep(randomSleepMs(rand, options));
                        }

                        if (failureStreak >= options.failureStreakCooldownThreshold) {
                            synchronized (System.out) {
                                System.out.println("--- worker " + (workerId + 1)
                                    + " failure streak cooldown "
                                    + options.failureStreakCooldownMs + "ms ---");
                            }
                            sleep(options.failureStreakCooldownMs);
                            failureStreak = 0;
                        }
                    }
                }
            }));
        }

        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("parallel fetch interrupted", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("parallel fetch failed", e.getCause());
            }
        }

        if (!failedIndexes.isEmpty()) {
            List<Integer> toRetry = new ArrayList<>(failedIndexes);
            Collections.sort(toRetry);
            System.out.println("\n=== 补偿重跑 " + toRetry.size() + " 只失败标的 ===");
            sleep(options.batchPauseMs);
            Random rand = new Random();
            for (Integer idx : toRetry) {
                String symbol = symbols.get(idx);
                FetchResult retry = fetchWithRetry(
                    symbol,
                    cacheDir,
                    fullFetcher,
                    incrementalFetcher,
                    options.retryCount,
                    options.retryBaseSleepMs
                );
                results[idx] = retry;
                System.out.printf("  补偿 %-12s %s%n", symbol, retry.status);
                sleep(randomSleepMs(rand, options));
            }
        }

        List<FetchResult> ordered = new ArrayList<>(total);
        for (FetchResult result : results) {
            if (result != null) ordered.add(result);
        }
        return ordered;
    }

    private static void normalizeOptions(FetchOptions options) {
        options.batchSize = Math.max(1, options.batchSize);
        options.retryCount = Math.max(0, options.retryCount);
        options.retryBaseSleepMs = Math.max(0, options.retryBaseSleepMs);
        options.batchPauseMs = Math.max(0, options.batchPauseMs);
        options.failureStreakCooldownThreshold = Math.max(1, options.failureStreakCooldownThreshold);
        options.failureStreakCooldownMs = Math.max(0, options.failureStreakCooldownMs);
        options.workers = Math.max(1, options.workers);
    }

    private static FetchResult fetchWithRetry(String symbol, String cacheDir,
                                               Function<String, List<DailyBar>> fullFetcher,
                                               Function<String, List<DailyBar>> incrementalFetcher,
                                               int maxRetry,
                                               int retryBaseSleepMs) {
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            FetchResult r = fetchOne(symbol, cacheDir, fullFetcher, incrementalFetcher);
            if (r.status != Status.FAILED) return r;
            if (attempt < maxRetry) {
                int backoff = retryBaseSleepMs * (attempt + 1);
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

    private static int randomSleepMs(Random rand, FetchOptions options) {
        if (options.maxSleepMs <= options.minSleepMs) {
            return options.minSleepMs;
        }
        return options.minSleepMs + rand.nextInt(options.maxSleepMs - options.minSleepMs + 1);
    }
}
