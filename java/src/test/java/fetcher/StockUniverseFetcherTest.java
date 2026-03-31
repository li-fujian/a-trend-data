package fetcher;

import org.junit.Rule;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class StockUniverseFetcherTest {

    @Test
    public void testFilterByMarketCap() {
        // 50亿以下 → 过滤
        assertFalse(StockUniverseFetcher.isInRange(4_999_999_999L));
        // 50亿 → 保留
        assertTrue(StockUniverseFetcher.isInRange(5_000_000_000L));
        // 5000亿 → 保留
        assertTrue(StockUniverseFetcher.isInRange(500_000_000_000L));
        // 5000亿以上 → 过滤
        assertFalse(StockUniverseFetcher.isInRange(500_000_000_001L));
    }

    @Test
    public void testFilterST() {
        assertTrue(StockUniverseFetcher.isST("ST银行"));
        assertTrue(StockUniverseFetcher.isST("*ST科技"));
        assertFalse(StockUniverseFetcher.isST("贵州茅台"));
        assertFalse(StockUniverseFetcher.isST("招商银行"));
    }

    @Test
    public void testBuildSymbol() {
        assertEquals("sh600519", StockUniverseFetcher.buildSymbol("600519", 1));
        assertEquals("sz000858", StockUniverseFetcher.buildSymbol("000858", 0));
    }

    @Test
    public void testParseStockEntry() {
        StockUniverseFetcher.StockEntry entry =
            StockUniverseFetcher.parseEntry("600519", 1, "贵州茅台", 2100000000000L);
        assertEquals("sh600519", entry.symbol);
        assertEquals("贵州茅台", entry.name);
        assertEquals(2100000000000L, entry.marketCap);
    }

    @Rule
    public org.junit.rules.TemporaryFolder tmp = new org.junit.rules.TemporaryFolder();

    @Test
    public void testSaveAndLoad() throws Exception {
        java.io.File outputFile = new java.io.File(tmp.getRoot(), "stock-universe.json");
        java.util.List<StockUniverseFetcher.StockEntry> stocks = java.util.Arrays.asList(
            new StockUniverseFetcher.StockEntry("sh600519", "贵州茅台", 2100000000000L),
            new StockUniverseFetcher.StockEntry("sz000858", "五粮液", 580000000000L)
        );
        StockUniverseFetcher.save(stocks, outputFile.getAbsolutePath());

        java.util.List<StockUniverseFetcher.StockEntry> loaded =
            StockUniverseFetcher.load(outputFile.getAbsolutePath());

        assertEquals(2, loaded.size());
        assertEquals("sh600519", loaded.get(0).symbol);
        assertEquals("贵州茅台", loaded.get(0).name);
        assertEquals(2100000000000L, loaded.get(0).marketCap);
        assertEquals("sz000858", loaded.get(1).symbol);
    }

    @Test(expected = java.io.FileNotFoundException.class)
    public void testLoadThrowsWhenFileNotFound() throws Exception {
        StockUniverseFetcher.load("/nonexistent/path/stock-universe.json");
    }
}
