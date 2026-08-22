package cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDate;

import static org.junit.Assert.*;

public class KLineCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testFreshRequiresQfqAdjustment() throws Exception {
        File cacheFile = new File(tmp.getRoot(), "sh600519.json");
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("{\"symbol\":\"sh600519\",\"last_updated\":\""
                    + LocalDate.now()
                    + "\",\"bars\":[{\"date\":\""
                    + LocalDate.now()
                    + "\",\"close\":100.0}]}");
        }

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        assertFalse(cache.isFresh("sh600519"));
    }

    @Test
    public void testRefreshReplacesLegacyCacheWithoutMerge() throws Exception {
        File cacheFile = new File(tmp.getRoot(), "sh600519.json");
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("{\"symbol\":\"sh600519\",\"last_updated\":\"2023-01-01\","
                    + "\"bars\":[{\"date\":\"2023-01-01\",\"close\":50.0}]}");
        }

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        DailyBar fetched = new DailyBar();
        fetched.setDate("2024-01-01");
        fetched.setClose(100.0);
        cache.refresh("sh600519", symbol -> java.util.Collections.singletonList(fetched));

        java.util.List<DailyBar> bars = cache.load("sh600519");
        assertEquals(1, bars.size());
        assertEquals("2024-01-01", bars.get(0).getDate());
        assertTrue(cache.hasQfqAdjustment("sh600519"));
    }

    @Test
    public void testSaveSortsBarsAndUsesLatestDate() throws Exception {
        DailyBar older = new DailyBar();
        older.setDate("2026-06-03");
        older.setClose(10.0);
        DailyBar newer = new DailyBar();
        newer.setDate("2026-06-05");
        newer.setClose(12.0);

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        cache.save("sh600519", java.util.Arrays.asList(newer, older));

        java.util.List<DailyBar> bars = cache.load("sh600519");
        assertEquals("2026-06-03", bars.get(0).getDate());
        assertEquals("2026-06-05", bars.get(1).getDate());

        java.io.File cacheFile = new java.io.File(tmp.getRoot(), "sh600519.json");
        String json = new String(java.nio.file.Files.readAllBytes(cacheFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"last_updated\": \"2026-06-05\""));
    }

    @Test
    public void testSaveWritesQfqAdjustment() {
        DailyBar bar = new DailyBar();
        bar.setDate(LocalDate.now().toString());
        bar.setClose(100.0);

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        cache.save("sh600519", java.util.Collections.singletonList(bar));

        assertTrue(cache.isFresh("sh600519"));
    }

    @Test
    public void testSaveWritesVolumeSchema() throws Exception {
        DailyBar bar = new DailyBar();
        bar.setDate("2026-08-21");
        bar.setClose(10.0);
        bar.setVolume(1000.0);

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        cache.save("sh600519", java.util.Collections.singletonList(bar));

        java.io.File cacheFile = new java.io.File(tmp.getRoot(), "sh600519.json");
        String json = new String(java.nio.file.Files.readAllBytes(cacheFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema_version\": 2"));
        assertTrue(json.contains("\"volume_unit\": \"shares\""));
    }

    @Test
    public void testLegacyStarCacheIsNotFreshAndNeedsRebuild() throws Exception {
        File cacheFile = new File(tmp.getRoot(), "sh688256.json");
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("{\"symbol\":\"sh688256\",\"adjustment\":\"qfq\",\"last_updated\":\""
                    + LocalDate.now()
                    + "\",\"bars\":[{\"date\":\""
                    + LocalDate.now()
                    + "\",\"close\":1010.88,\"volume\":1485537000}]}");
        }

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        assertFalse(cache.isFresh("sh688256"));
        assertTrue(cache.needsFullHistoryRebuild("sh688256"));
        assertFalse(cache.canMerge("sh688256"));
        assertTrue(cache.hasQfqAdjustment("sh688256"));
    }

    @Test
    public void testMainBoardWithoutSchemaRemainsFresh() throws Exception {
        File cacheFile = new File(tmp.getRoot(), "sh600000.json");
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("{\"symbol\":\"sh600000\",\"adjustment\":\"qfq\",\"last_updated\":\""
                    + LocalDate.now()
                    + "\",\"bars\":[{\"date\":\""
                    + LocalDate.now()
                    + "\",\"close\":9.05,\"volume\":67301800}]}");
        }

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        assertTrue(cache.isFresh("sh600000"));
        assertFalse(cache.needsFullHistoryRebuild("sh600000"));
        assertTrue(cache.canMerge("sh600000"));
    }

    @Test
    public void testRefreshReplacesLegacyStarWithoutMerge() throws Exception {
        File cacheFile = new File(tmp.getRoot(), "sh688256.json");
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("{\"symbol\":\"sh688256\",\"adjustment\":\"qfq\","
                    + "\"last_updated\":\"2026-08-19\","
                    + "\"bars\":[{\"date\":\"2026-08-19\",\"close\":1050.0,\"volume\":1574639600}]}");
        }

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        DailyBar fetched = new DailyBar();
        fetched.setDate("2026-08-20");
        fetched.setClose(1010.88);
        fetched.setVolume(14_855_370.0);
        cache.refresh("sh688256", symbol -> java.util.Collections.singletonList(fetched));

        java.util.List<DailyBar> bars = cache.load("sh688256");
        assertEquals(1, bars.size());
        assertEquals("2026-08-20", bars.get(0).getDate());
        assertEquals(14_855_370.0, bars.get(0).getVolume(), 0.0001);
        assertTrue(cache.hasCurrentVolumeSchema("sh688256"));
        assertFalse(cache.needsFullHistoryRebuild("sh688256"));
    }
}
