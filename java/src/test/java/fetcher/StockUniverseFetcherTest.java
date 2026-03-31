package fetcher;

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
}
