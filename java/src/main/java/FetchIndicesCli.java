import fetcher.BulkKLineFetcher;
import monitor.trendfollowing.SinaKLineFetcher;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * 补拉指数日线到 cache/kline/（默认：上证指数 sh000001、沪深300 sh000300）。
 *
 * <pre>
 * cd java
 * mvn -q compile exec:java -Dexec.mainClass=FetchIndicesCli \
 *   "-Dexec.args=--repo-root /path/to/a-trend-data"
 * </pre>
 */
public class FetchIndicesCli {

    public static void main(String[] args) {
        String repoRoot = resolveRepoRoot(args);
        String cacheDir = repoRoot + "/cache/kline";
        List<String> symbols = Arrays.asList("sh000001", "sh000300");
        System.out.println("Repo: " + repoRoot);
        System.out.println("Cache: " + cacheDir);
        for (String sym : symbols) {
            BulkKLineFetcher.FetchResult r =
                    BulkKLineFetcher.fetchOne(sym, cacheDir, SinaKLineFetcher::fetch);
            System.out.println(sym + " -> " + r.status);
        }
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
