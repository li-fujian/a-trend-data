import fetcher.BulkKLineFetcher;
import monitor.trendfollowing.TencentQfqKLineFetcher;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 拉取主要指数日线到 cache/kline/。
 * 默认标的：上证指数 sh000001、沪深300 sh000300、中证500 sh000905、科创50 sh000688、创业板指 sz399006。
 *
 * <pre>
 * cd java
 * mvn -q compile exec:java -Dexec.mainClass=FetchIndicesCli \
 *   "-Dexec.args=--repo-root /path/to/a-trend-data"
 * </pre>
 */
public class FetchIndicesCli {

    /** 与 {@link DataUpdateCli} 每日任务一致的主要指数 */
    public static final List<String> DAILY_INDEX_SYMBOLS =
            Collections.unmodifiableList(Arrays.asList(
                    "sh000001", // 上证指数
                    "sh000300", // 沪深300
                    "sh000905", // 中证500
                    "sh000688", // 科创50
                    "sz399006"  // 创业板指
            ));

    /**
     * 拉取 {@link #DAILY_INDEX_SYMBOLS}；与股票共用 merge 缓存策略。
     * 使用较大 datalen，便于首次落盘尽量长的历史、日常增量合并。
     */
    public static void fetchToCache(String cacheDir) {
        for (String sym : DAILY_INDEX_SYMBOLS) {
            BulkKLineFetcher.FetchResult r = BulkKLineFetcher.fetchOne(sym, cacheDir,
                    s -> TencentQfqKLineFetcher.fetch(s, TencentQfqKLineFetcher.MAX_DATALEN));
            System.out.println("  index " + sym + " -> " + r.status);
        }
    }

    public static void main(String[] args) {
        String repoRoot = resolveRepoRoot(args);
        String cacheDir = repoRoot + "/cache/kline";
        System.out.println("Repo: " + repoRoot);
        System.out.println("Cache: " + cacheDir);
        fetchToCache(cacheDir);
    }

    private static String resolveRepoRoot(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--repo-root".equals(args[i])) {
                return args[i + 1];
            }
        }
        try {
            return new File("..").getCanonicalPath();
        } catch (java.io.IOException e) {
            return new File("..").getAbsolutePath();
        }
    }
}
