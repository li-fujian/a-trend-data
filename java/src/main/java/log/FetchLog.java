package log;

import com.google.gson.*;
import java.io.*;
import java.util.*;

/**
 * 追加写拉取日志到 logs/fetch-log.json，保留最近 30 条。
 * 同一天的记录只保留最新一条（覆盖）。
 */
public class FetchLog {

    private static final int MAX_ENTRIES = 30;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class LogEntry {
        public String date;
        public String started_at;
        public String finished_at;
        public int total;
        public int ok;
        public int skipped;
        public int failed;
        public List<String> failed_symbols;

        public LogEntry(String date, String startedAt, String finishedAt,
                        int total, int ok, int skipped, int failed,
                        List<String> failedSymbols) {
            this.date = date;
            this.started_at = startedAt;
            this.finished_at = finishedAt;
            this.total = total;
            this.ok = ok;
            this.skipped = skipped;
            this.failed = failed;
            this.failed_symbols = failedSymbols;
        }
    }

    /**
     * 追加一条日志。同一天的记录覆盖旧条目，超出30条截断头部。
     */
    public static void append(String logPath, LogEntry entry) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        File file = new File(logPath);
        if (file.exists()) {
            entries = load(logPath);
        }

        // 移除同一天的旧记录
        entries.removeIf(e -> entry.date.equals(e.date));
        entries.add(entry);

        // 保留最近 MAX_ENTRIES 条
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
        }

        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            GSON.toJson(entries, writer);
        }
    }

    /**
     * 读取日志列表。
     */
    public static List<LogEntry> load(String logPath) throws IOException {
        File file = new File(logPath);
        if (!file.exists()) return new ArrayList<>();
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            LogEntry[] arr = GSON.fromJson(reader, LogEntry[].class);
            return arr != null ? new ArrayList<>(Arrays.asList(arr)) : new ArrayList<>();
        }
    }
}
