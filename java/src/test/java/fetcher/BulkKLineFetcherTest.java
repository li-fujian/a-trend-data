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
}
