package monitor.trendfollowing;

import cache.DailyBar;
import org.junit.Test;

import static org.junit.Assert.*;

public class EastmoneyQfqKLineFetcherTest {

    @Test
    public void testToSecid() {
        assertEquals("1.600519", EastmoneyQfqKLineFetcher.toSecid("sh600519"));
        assertEquals("0.000858", EastmoneyQfqKLineFetcher.toSecid("sz000858"));
        assertNull(EastmoneyQfqKLineFetcher.toSecid("bj430047"));
    }

    @Test
    public void testParseKline() {
        DailyBar bar = EastmoneyQfqKLineFetcher.parseKline(
                "2025-07-25,20.400,20.270,20.400,20.160,38465,77993200.00,1.19,-0.55,-0.11,2.40"
        );

        assertNotNull(bar);
        assertEquals("2025-07-25", bar.getDate());
        assertEquals(20.400, bar.getOpen(), 0.0001);
        assertEquals(20.400, bar.getHigh(), 0.0001);
        assertEquals(20.160, bar.getLow(), 0.0001);
        assertEquals(20.270, bar.getClose(), 0.0001);
        assertEquals(3_846_500.0, bar.getVolume(), 0.0001);
    }
}
