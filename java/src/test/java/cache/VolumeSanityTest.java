package cache;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class VolumeSanityTest {

    private static final long CAMBRICON_CAP = 650_864_000_000L; // ~6508.64 亿

    @Test
    public void testRejectsHundredXStarVolume() {
        DailyBar bar = bar("2026-08-20", 1010.88, 1_485_537_000.0);
        String reason = VolumeSanity.explainReject("sh688256", Arrays.asList(bar), CAMBRICON_CAP);
        assertNotNull(reason);
        assertTrue(reason.contains("sh688256"));
    }

    @Test
    public void testAcceptsCorrectStarVolume() {
        DailyBar bar = bar("2026-08-20", 1010.88, 14_855_370.0);
        assertTrue(VolumeSanity.isPlausible("sh688256", Arrays.asList(bar), CAMBRICON_CAP));
    }

    @Test
    public void testSkipsCheckWithoutMarketCap() {
        DailyBar bar = bar("2026-08-20", 1010.88, 1_485_537_000.0);
        assertTrue(VolumeSanity.isPlausible("sh688256", Arrays.asList(bar), null));
        assertTrue(VolumeSanity.isPlausible("sh688256", Arrays.asList(bar), 0L));
    }

    @Test
    public void testRejectsPersistentHundredPercentTurnover() {
        List<DailyBar> bars = new ArrayList<DailyBar>();
        for (int i = 1; i <= 20; i++) {
            bars.add(bar(String.format("2026-07-%02d", i), 10.0, 150_000_000.0));
        }
        long cap = 1_000_000_000L; // 10 * 1.5e8 / 1e9 = 1.5
        String reason = VolumeSanity.explainReject("sh688001", bars, cap);
        assertNotNull(reason);
        assertTrue(reason.contains("last"));
    }

    private static DailyBar bar(String date, double close, double volume) {
        DailyBar bar = new DailyBar();
        bar.setDate(date);
        bar.setOpen(close);
        bar.setHigh(close);
        bar.setLow(close);
        bar.setClose(close);
        bar.setVolume(volume);
        return bar;
    }
}
