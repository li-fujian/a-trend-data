package log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class FetchLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testAppendCreatesFile() throws Exception {
        File logFile = new File(tmp.getRoot(), "fetch-log.json");
        FetchLog.LogEntry entry = new FetchLog.LogEntry(
            "2026-03-31", "2026-03-31T18:00:00", "2026-03-31T18:20:00",
            1500, 1480, 10, 10, Arrays.asList("sh603001")
        );
        FetchLog.append(logFile.getAbsolutePath(), entry);
        assertTrue(logFile.exists());
    }

    @Test
    public void testAppendKeepsLast30() throws Exception {
        File logFile = new File(tmp.getRoot(), "fetch-log.json");
        for (int i = 0; i < 35; i++) {
            FetchLog.LogEntry entry = new FetchLog.LogEntry(
                "2026-01-" + String.format("%02d", (i % 31) + 1),
                "2026-01-01T00:00:00", "2026-01-01T00:20:00",
                100, 100, 0, 0, Arrays.asList()
            );
            FetchLog.append(logFile.getAbsolutePath(), entry);
        }
        List<FetchLog.LogEntry> entries = FetchLog.load(logFile.getAbsolutePath());
        assertEquals(30, entries.size());
    }

    @Test
    public void testAppendIsIdempotentForSameDate() throws Exception {
        File logFile = new File(tmp.getRoot(), "fetch-log.json");
        FetchLog.LogEntry entry1 = new FetchLog.LogEntry(
            "2026-03-31", "2026-03-31T18:00:00", "2026-03-31T18:10:00",
            100, 90, 5, 5, Arrays.asList("sh001")
        );
        FetchLog.LogEntry entry2 = new FetchLog.LogEntry(
            "2026-03-31", "2026-03-31T19:00:00", "2026-03-31T19:05:00",
            100, 98, 2, 0, Arrays.asList()
        );
        FetchLog.append(logFile.getAbsolutePath(), entry1);
        FetchLog.append(logFile.getAbsolutePath(), entry2);
        List<FetchLog.LogEntry> entries = FetchLog.load(logFile.getAbsolutePath());
        // 同一天第二次写入应覆盖第一条
        assertEquals(1, entries.size());
        assertEquals(98, entries.get(0).ok);
    }
}
