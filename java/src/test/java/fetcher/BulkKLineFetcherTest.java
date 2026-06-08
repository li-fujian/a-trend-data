package fetcher;

import cache.DailyBar;
import cache.KLineCache;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class BulkKLineFetcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testSkipFreshSymbol() throws Exception {
        String cacheDir = tmp.getRoot().getAbsolutePath();
        KLineCache cache = new KLineCache(cacheDir);

        // 写一条今天的缓存
        DailyBar bar = new DailyBar();
        bar.setDate(java.time.LocalDate.now().toString());
        bar.setClose(100.0);
        cache.save("sh600519", Collections.singletonList(bar));

        BulkKLineFetcher.FetchResult result = BulkKLineFetcher.fetchOne(
            "sh600519", cacheDir, symbol -> Collections.emptyList()
        );
        assertEquals(BulkKLineFetcher.Status.SKIPPED, result.status);
    }

    @Test
    public void testFetchSucceeds() throws Exception {
        String cacheDir = tmp.getRoot().getAbsolutePath();

        DailyBar bar = new DailyBar();
        bar.setDate("2026-03-31");
        bar.setClose(100.0);

        BulkKLineFetcher.FetchResult result = BulkKLineFetcher.fetchOne(
            "sh600519", cacheDir, symbol -> Collections.singletonList(bar)
        );
        assertEquals(BulkKLineFetcher.Status.OK, result.status);
    }

    @Test
    public void testFetchFailsOnEmptyResponse() throws Exception {
        String cacheDir = tmp.getRoot().getAbsolutePath();

        BulkKLineFetcher.FetchResult result = BulkKLineFetcher.fetchOne(
            "sh600519", cacheDir, symbol -> Collections.emptyList()
        );
        assertEquals(BulkKLineFetcher.Status.FAILED, result.status);
    }

    @Test
    public void testBuildSummary() {
        List<BulkKLineFetcher.FetchResult> results = Arrays.asList(
            new BulkKLineFetcher.FetchResult("sh600519", BulkKLineFetcher.Status.OK),
            new BulkKLineFetcher.FetchResult("sh600036", BulkKLineFetcher.Status.SKIPPED),
            new BulkKLineFetcher.FetchResult("sz000001", BulkKLineFetcher.Status.FAILED)
        );
        BulkKLineFetcher.Summary summary = BulkKLineFetcher.buildSummary(results);
        assertEquals(1, summary.ok);
        assertEquals(1, summary.skipped);
        assertEquals(1, summary.failed);
        assertEquals(Collections.singletonList("sz000001"), summary.failedSymbols);
    }

    @Test
    public void testFetchAllRetryOnFailure() throws Exception {
        String cacheDir = tmp.getRoot().getAbsolutePath();
        // fetcher 第一次失败，第二次成功（模拟重试成功）
        final int[] callCount = {0};
        DailyBar bar = new DailyBar();
        bar.setDate("2026-03-31");
        bar.setClose(100.0);

        List<BulkKLineFetcher.FetchResult> results = BulkKLineFetcher.fetchAll(
            Collections.singletonList("sh600519"),
            cacheDir,
            symbol -> {
                callCount[0]++;
                if (callCount[0] < 2) return Collections.emptyList(); // 第一次失败
                return Collections.singletonList(bar); // 第二次成功
            },
            fastOptions()
        );

        assertEquals(1, results.size());
        assertEquals(BulkKLineFetcher.Status.OK, results.get(0).status);
    }

    @Test
    public void testFetchAllCompensationPass() throws Exception {
        String cacheDir = tmp.getRoot().getAbsolutePath();
        // fetcher 前3次（原始+2次重试）全部失败，第4次（补偿轮）成功
        final int[] callCount = {0};
        DailyBar bar = new DailyBar();
        bar.setDate("2026-03-31");
        bar.setClose(100.0);

        List<BulkKLineFetcher.FetchResult> results = BulkKLineFetcher.fetchAll(
            Collections.singletonList("sh600519"),
            cacheDir,
            symbol -> {
                callCount[0]++;
                if (callCount[0] <= 3) return Collections.emptyList(); // 前3次失败
                return Collections.singletonList(bar); // 第4次成功
            },
            fastOptions()
        );

        assertEquals(1, results.size());
        assertEquals(BulkKLineFetcher.Status.OK, results.get(0).status);
    }

    private BulkKLineFetcher.FetchOptions fastOptions() {
        BulkKLineFetcher.FetchOptions options = BulkKLineFetcher.FetchOptions.defaults()
            .withSleepRange(0, 0);
        options.batchPauseMs = 0;
        options.retryBaseSleepMs = 0;
        options.failureStreakCooldownMs = 0;
        return options;
    }
}
