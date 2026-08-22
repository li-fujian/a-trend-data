package monitor.trendfollowing;

import cache.DailyBar;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TencentQfqKLineFetcherTest {

    @Test
    public void testParseResponse() {
        String body = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2026-06-05\",\"1278.000\",\"1272.860\",\"1283.000\",\"1267.740\",\"31304.000\""
                + "]]}}}";

        List<DailyBar> bars = TencentQfqKLineFetcher.parseResponse("sh600519", body);
        assertEquals(1, bars.size());

        DailyBar bar = bars.get(0);
        assertEquals("2026-06-05", bar.getDate());
        assertEquals(1278.000, bar.getOpen(), 0.0001);
        assertEquals(1272.860, bar.getClose(), 0.0001);
        assertEquals(1283.000, bar.getHigh(), 0.0001);
        assertEquals(1267.740, bar.getLow(), 0.0001);
        assertEquals(3_130_400.0, bar.getVolume(), 0.0001);
    }

    @Test
    public void testParseIndexResponseKeepsVolume() {
        String body = "kline_dayqfq={\"code\":0,\"data\":{\"sh000001\":{\"day\":[["
                + "\"2026-06-05\",\"4044.830\",\"4027.740\",\"4078.930\",\"4015.060\",\"662918577.000\""
                + "]]}}}";

        List<DailyBar> bars = TencentQfqKLineFetcher.parseResponse("sh000001", body);
        assertEquals(1, bars.size());
        assertEquals(662918577.0, bars.get(0).getVolume(), 0.0001);
    }

    @Test
    public void testSkipsInvalidOhlc() {
        String negativeClose = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2007-12-24\",\"-127.852\",\"-132.735\",\"-127.799\",\"-133.036\",\"21203.790\""
                + "]]}}}";
        String negativeOpen = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2016-06-03\",\"-2.731\",\"14.159\",\"17.009\",\"-3.331\",\"64798.000\""
                + "]]}}}";

        assertTrue(TencentQfqKLineFetcher.parseResponse("sh600519", negativeClose).isEmpty());
        assertTrue(TencentQfqKLineFetcher.parseResponse("sh600519", negativeOpen).isEmpty());
    }

    @Test
    public void testEarliestBarDateUsesMinNotFirst() {
        DailyBar newer = new DailyBar();
        newer.setDate("2026-06-05");
        DailyBar older = new DailyBar();
        older.setDate("2026-06-03");

        assertEquals("2026-06-03", TencentQfqKLineFetcher.earliestBarDate(
                java.util.Arrays.asList(newer, older)));
    }

    @Test
    public void testMergeMultipleChunksSortedAscending() {
        String newerChunk = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2026-06-04\",\"10\",\"11\",\"12\",\"9\",\"100\"],"
                + "[\"2026-06-05\",\"11\",\"12\",\"13\",\"10\",\"100\"]"
                + "]}}}";
        String olderChunk = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2026-06-01\",\"8\",\"9\",\"10\",\"7\",\"100\"],"
                + "[\"2026-06-02\",\"9\",\"10\",\"11\",\"8\",\"100\"]"
                + "]}}}";

        java.util.Map<String, DailyBar> byDate = new java.util.LinkedHashMap<>();
        for (DailyBar bar : TencentQfqKLineFetcher.parseResponse("sh600519", newerChunk)) {
            byDate.put(bar.getDate(), bar);
        }
        for (DailyBar bar : TencentQfqKLineFetcher.parseResponse("sh600519", olderChunk)) {
            byDate.put(bar.getDate(), bar);
        }

        java.util.List<DailyBar> merged = new java.util.ArrayList<>(byDate.values());
        merged.sort(java.util.Comparator.comparing(DailyBar::getDate));

        assertEquals(4, merged.size());
        assertEquals("2026-06-01", merged.get(0).getDate());
        assertEquals("2026-06-05", merged.get(merged.size() - 1).getDate());
    }

    @Test
    public void testFetchSortsByDateAscending() {
        String body1 = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2026-06-04\",\"1\",\"2\",\"3\",\"4\",\"100\""
                + "]]}}}";
        String body2 = "kline_dayqfq={\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":[["
                + "\"2026-06-03\",\"1\",\"2\",\"3\",\"4\",\"100\""
                + "]]}}}";

        List<DailyBar> newer = TencentQfqKLineFetcher.parseResponse("sh600519", body1);
        List<DailyBar> older = TencentQfqKLineFetcher.parseResponse("sh600519", body2);
        newer.addAll(older);
        newer.sort(java.util.Comparator.comparing(DailyBar::getDate));

        assertEquals("2026-06-03", newer.get(0).getDate());
        assertEquals("2026-06-04", newer.get(1).getDate());
    }

    @Test
    public void testParseEmptyDataArray() {
        String body = "kline_dayqfq={\"code\":0,\"msg\":\"param error\",\"data\":[]}";
        assertTrue(TencentQfqKLineFetcher.parseResponse("sh000001", body).isEmpty());
    }

    @Test
    public void testIsIndex() {
        assertTrue(TencentQfqKLineFetcher.isIndex("sh000001"));
        assertTrue(TencentQfqKLineFetcher.isIndex("sz399006"));
        assertFalse(TencentQfqKLineFetcher.isIndex("sh600519"));
        assertFalse(TencentQfqKLineFetcher.isIndex("sh688256"));
    }

    @Test
    public void testParseStarBoardDoesNotScaleVolume() {
        String body = "kline_dayqfq={\"code\":0,\"data\":{\"sh688256\":{\"qfqday\":[["
                + "\"2026-08-20\",\"1060.020\",\"1010.880\",\"1068.900\",\"995.000\",\"14855370.000\""
                + "]]}}}";

        List<DailyBar> bars = TencentQfqKLineFetcher.parseResponse("sh688256", body);
        assertEquals(1, bars.size());
        assertEquals(14_855_370.0, bars.get(0).getVolume(), 0.0001);
    }

    @Test
    public void testParseStarCdrDoesNotScaleVolume() {
        String body = "kline_dayqfq={\"code\":0,\"data\":{\"sh689009\":{\"qfqday\":[["
                + "\"2026-08-21\",\"43.50\",\"43.60\",\"44.00\",\"43.00\",\"1000001.000\""
                + "]]}}}";

        List<DailyBar> bars = TencentQfqKLineFetcher.parseResponse("sh689009", body);
        assertEquals(1, bars.size());
        assertEquals(1_000_001.0, bars.get(0).getVolume(), 0.0001);
    }

    @Test
    public void testParseQuoteDetectsShareVolumeForStar() {
        String body = "kline_dayqfq={\"code\":0,\"data\":{\"sh688256\":{"
                + "\"qfqday\":[[\"2026-08-21\",\"999.980\",\"1035.000\",\"1061.470\",\"996.000\",\"12033293.000\"]],"
                + "\"qt\":{\"sh688256\":[\"1\",\"寒武纪\",\"688256\",\"1035.00\","
                + "\"1010.88\",\"999.98\",\"12033293\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\","
                + "\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\","
                + "\"0\",\"0\",\"0\",\"0\",\"0\",\"1035.00/12033293/12446848997\"]}}}}";

        List<DailyBar> bars = TencentQfqKLineFetcher.parseResponse("sh688256", body);
        assertEquals(1, bars.size());
        assertEquals(12_033_293.0, bars.get(0).getVolume(), 0.0001);
        assertEquals(Boolean.FALSE,
                TencentQfqKLineFetcher.parseResponse("sh688256", body, null).scaleVolume);
    }

    @Test
    public void testParseQuoteDetectsLotVolumeForMainBoard() {
        String body = "kline_dayqfq={\"code\":0,\"data\":{\"sh600000\":{"
                + "\"qfqday\":[[\"2026-08-21\",\"9.090\",\"9.050\",\"9.150\",\"9.030\",\"512703.000\"]],"
                + "\"qt\":{\"sh600000\":[\"1\",\"浦发银行\",\"600000\",\"9.05\","
                + "\"9.11\",\"9.09\",\"512703\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\","
                + "\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\","
                + "\"0\",\"0\",\"0\",\"0\",\"0\",\"9.05/512703/465159863\"]}}}}";

        List<DailyBar> bars = TencentQfqKLineFetcher.parseResponse("sh600000", body);
        assertEquals(1, bars.size());
        assertEquals(51_270_300.0, bars.get(0).getVolume(), 0.0001);
    }
}
