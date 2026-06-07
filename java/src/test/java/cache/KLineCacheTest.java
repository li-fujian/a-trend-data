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
}
