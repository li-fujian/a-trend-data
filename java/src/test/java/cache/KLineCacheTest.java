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
    public void testSaveWritesQfqAdjustment() {
        DailyBar bar = new DailyBar();
        bar.setDate(LocalDate.now().toString());
        bar.setClose(100.0);

        KLineCache cache = new KLineCache(tmp.getRoot().getAbsolutePath());
        cache.save("sh600519", java.util.Collections.singletonList(bar));

        assertTrue(cache.isFresh("sh600519"));
    }
}
