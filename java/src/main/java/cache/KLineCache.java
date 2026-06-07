package cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * Disk-based cache for daily K-line data.
 * Each symbol stored in separate JSON file: {cacheDir}/{symbol}.json
 *
 * Features:
 * - Incremental merge: deduplicates by date, fetched data wins on conflict
 * - Freshness check: cache is fresh if last_updated == today
 * - Error handling: returns empty list on failure, logs to stderr
 */
public class KLineCache {
    public static final String ADJUSTMENT = "qfq";

    private final String cacheDir;
    private final Gson gson;

    public KLineCache(String cacheDir) {
        this.cacheDir = cacheDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Validate symbol parameter for security and robustness
     * @param symbol stock symbol to validate
     * @throws IllegalArgumentException if symbol is null, empty, or contains unsafe characters
     */
    private void validateSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("Symbol cannot be null or empty");
        }
        if (symbol.contains("/") || symbol.contains("\\") || symbol.contains("..")) {
            throw new IllegalArgumentException("Symbol contains invalid characters (path traversal attempt): " + symbol);
        }
    }

    /**
     * Save bars to cache file with last_updated timestamp
     * last_updated is automatically determined from the last bar's date
     *
     * @param symbol stock symbol (e.g., "600519")
     * @param bars list of daily bars to save
     * @throws IllegalArgumentException if symbol is invalid or bars list is null or empty
     */
    public void save(String symbol, List<DailyBar> bars) {
        validateSymbol(symbol);

        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("Bars list cannot be null or empty");
        }

        File dir = new File(cacheDir);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Failed to create cache directory: " + cacheDir);
            return;
        }

        File cacheFile = new File(dir, symbol + ".json");
        CacheFile cf = new CacheFile();
        cf.symbol = symbol;
        cf.adjustment = ADJUSTMENT;

        List<DailyBar> sorted = new ArrayList<>(bars);
        sorted.sort(new Comparator<DailyBar>() {
            @Override
            public int compare(DailyBar b1, DailyBar b2) {
                String d1 = b1.getDate();
                String d2 = b2.getDate();
                if (d1 == null) return d2 == null ? 0 : -1;
                if (d2 == null) return 1;
                return d1.compareTo(d2);
            }
        });

        cf.last_updated = sorted.stream()
                .map(DailyBar::getDate)
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("Bars must contain at least one dated bar"));

        cf.bars = sorted;

        try (FileWriter writer = new FileWriter(cacheFile)) {
            gson.toJson(cf, writer);
        } catch (IOException e) {
            System.err.println("Failed to save cache for " + symbol + ": " + e.getMessage());
        }
    }

    /**
     * Load bars from cache file
     *
     * @param symbol stock symbol
     * @return list of daily bars, or empty list if file not found or error occurs
     * @throws IllegalArgumentException if symbol is invalid
     */
    public List<DailyBar> load(String symbol) {
        validateSymbol(symbol);

        File cacheFile = new File(cacheDir, symbol + ".json");
        if (!cacheFile.exists()) {
            return new ArrayList<>();
        }

        try (FileReader reader = new FileReader(cacheFile)) {
            CacheFile cf = gson.fromJson(reader, CacheFile.class);
            return cf != null && cf.bars != null ? cf.bars : new ArrayList<>();
        } catch (Exception e) {
            System.err.println("Failed to load cache for " + symbol + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Check if cache is fresh (last_updated == today)
     * Today is automatically determined using LocalDate.now()
     *
     * @param symbol stock symbol
     * @return true if cache exists and last_updated == today
     * @throws IllegalArgumentException if symbol is invalid
     */
    public boolean isFresh(String symbol) {
        validateSymbol(symbol);

        File cacheFile = new File(cacheDir, symbol + ".json");
        if (!cacheFile.exists()) {
            return false;
        }

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        try (FileReader reader = new FileReader(cacheFile)) {
            CacheFile cf = gson.fromJson(reader, CacheFile.class);
            return cf != null
                && today.equals(cf.last_updated)
                && ADJUSTMENT.equals(cf.adjustment);
        } catch (Exception e) {
            System.err.println("Failed to check freshness for " + symbol + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Refresh cache: load cached bars, fetch new data, merge, and save
     *
     * @param symbol stock symbol
     * @param fetcher function to fetch bars from network (symbol -> bars)
     * @throws IllegalArgumentException if symbol is invalid
     */
    public void refresh(String symbol, Function<String, List<DailyBar>> fetcher) {
        validateSymbol(symbol);

        List<DailyBar> fetched = fetcher.apply(symbol);
        if (fetched == null || fetched.isEmpty()) {
            return;
        }

        List<DailyBar> toSave = hasQfqAdjustment(symbol)
            ? mergeBars(load(symbol), fetched)
            : fetched;

        save(symbol, toSave);
    }

    /**
     * True when cache file exists and was written with forward-adjusted (qfq) prices.
     */
    boolean hasQfqAdjustment(String symbol) {
        validateSymbol(symbol);

        File cacheFile = new File(cacheDir, symbol + ".json");
        if (!cacheFile.exists()) {
            return false;
        }

        try (FileReader reader = new FileReader(cacheFile)) {
            CacheFile cf = gson.fromJson(reader, CacheFile.class);
            return cf != null && ADJUSTMENT.equals(cf.adjustment);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Merge cached and fetched bars with deduplication
     * On conflict (same date), fetched data wins
     * Result is sorted by date ascending
     *
     * @param cached bars from cache
     * @param fetched bars from network fetch
     * @return merged list, deduplicated and sorted by date
     */
    public static List<DailyBar> mergeBars(List<DailyBar> cached, List<DailyBar> fetched) {
        // Use LinkedHashMap to preserve insertion order and deduplicate
        Map<String, DailyBar> map = new LinkedHashMap<>();

        // Add cached bars first
        if (cached != null) {
            for (DailyBar bar : cached) {
                if (bar.getDate() != null) {
                    map.put(bar.getDate(), bar);
                }
            }
        }

        // Add fetched bars (will overwrite on conflict - fetched wins)
        if (fetched != null) {
            for (DailyBar bar : fetched) {
                if (bar.getDate() != null) {
                    map.put(bar.getDate(), bar);
                }
            }
        }

        // Convert to list and sort by date
        List<DailyBar> result = new ArrayList<>(map.values());
        result.sort(new Comparator<DailyBar>() {
            @Override
            public int compare(DailyBar b1, DailyBar b2) {
                String d1 = b1.getDate();
                String d2 = b2.getDate();
                if (d1 == null) return d2 == null ? 0 : -1;
                if (d2 == null) return 1;
                return d1.compareTo(d2);
            }
        });

        return result;
    }

    /**
     * Inner class representing the JSON structure of cache files
     */
    static class CacheFile {
        String symbol;
        String adjustment;
        String last_updated;
        List<DailyBar> bars;
    }
}
