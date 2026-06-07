package monitor.trendfollowing;

import cache.DailyBar;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches daily A-share K-lines from Tencent Finance using forward-adjusted prices.
 *
 * <p>Endpoint: {@code web.ifzq.gtimg.cn/appstock/app/fqkline/get} with {@code qfq} adjust type.
 * Each bar is {@code [date, open, close, high, low, volume]}; stock volume is in lots (手),
 * multiplied by 100 to match cache units (shares).
 */
public class TencentQfqKLineFetcher {

    public static final int MAX_DATALEN = 5000;
    /** Tencent rejects index requests above ~2000 bars (returns param error). */
    public static final int INDEX_MAX_DATALEN = 2000;
    static final int CHUNK_SIZE = 640;

    private static final String URL =
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
                    + "?_var=kline_dayqfq"
                    + "&param=%s,day,%s,%s,%d,qfq";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    public static List<DailyBar> fetch(String symbol) {
        return fetch(symbol, MAX_DATALEN);
    }

    public static List<DailyBar> fetch(String symbol, int datalen) {
        if (!isSupportedSymbol(symbol)) {
            return new ArrayList<>();
        }

        int maxLen = isIndex(symbol) ? INDEX_MAX_DATALEN : MAX_DATALEN;
        int target = Math.max(1, Math.min(datalen, maxLen));
        int chunk = isIndex(symbol) ? target : CHUNK_SIZE;

        Map<String, DailyBar> byDate = new LinkedHashMap<>();
        String endDate = "";

        while (byDate.size() < target) {
            int requestSize = Math.min(chunk, target - byDate.size());
            List<DailyBar> chunkBars = fetchChunk(symbol, "", endDate, requestSize);
            if (chunkBars.isEmpty()) {
                break;
            }

            for (DailyBar bar : chunkBars) {
                if (bar.getDate() != null) {
                    byDate.put(bar.getDate(), bar);
                }
            }

            if (chunkBars.size() < requestSize) {
                break;
            }

            String earliest = earliestBarDate(chunkBars);
            if (earliest == null || earliest.equals(endDate)) {
                break;
            }

            LocalDate nextEnd = LocalDate.parse(earliest, DATE_FMT).minusDays(1);
            endDate = nextEnd.format(DATE_FMT);
        }

        List<DailyBar> result = new ArrayList<>(byDate.values());
        result.sort(Comparator.comparing(DailyBar::getDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        return result;
    }

    private static List<DailyBar> fetchChunk(String symbol, String startDate, String endDate, int count) {
        String url = String.format(URL, symbol, startDate, endDate, count);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://finance.qq.com/");

        try {
            String body = utils.HttpClientPool.getHttpClient().get(url, headers);
            return parseResponse(symbol, body);
        } catch (Exception e) {
            System.err.println("Failed to fetch qfq K-line for " + symbol + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    static List<DailyBar> parseResponse(String symbol, String body) {
        List<DailyBar> bars = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return bars;
        }

        String json = body.trim();
        int eq = json.indexOf('=');
        if (eq >= 0) {
            json = json.substring(eq + 1);
        }

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        if (root == null || !root.has("data")) {
            return bars;
        }

        JsonElement dataElem = root.get("data");
        if (dataElem == null || dataElem.isJsonArray()) {
            return bars;
        }

        JsonObject data = dataElem.getAsJsonObject();
        if (!data.has(symbol) || !data.get(symbol).isJsonObject()) {
            return bars;
        }

        JsonObject symbolData = data.getAsJsonObject(symbol);
        JsonArray rows = null;
        if (symbolData.has("qfqday")) {
            rows = symbolData.getAsJsonArray("qfqday");
        } else if (symbolData.has("day")) {
            rows = symbolData.getAsJsonArray("day");
        }

        if (rows == null) {
            return bars;
        }

        boolean scaleVolume = !isIndex(symbol);
        for (JsonElement row : rows) {
            DailyBar bar = parseBar(row, scaleVolume);
            if (bar != null) {
                bars.add(bar);
            }
        }
        return bars;
    }

    static DailyBar parseBar(JsonElement row, boolean scaleVolume) {
        if (row == null || !row.isJsonArray()) {
            return null;
        }
        JsonArray parts = row.getAsJsonArray();
        if (parts.size() < 6) {
            return null;
        }

        DailyBar bar = new DailyBar();
        bar.setDate(parts.get(0).getAsString());
        bar.setOpen(parseDouble(parts.get(1).getAsString()));
        bar.setClose(parseDouble(parts.get(2).getAsString()));
        bar.setHigh(parseDouble(parts.get(3).getAsString()));
        bar.setLow(parseDouble(parts.get(4).getAsString()));

        Double volume = parseDouble(parts.get(5).getAsString());
        if (volume != null && scaleVolume) {
            volume = volume * 100.0;
        }
        bar.setVolume(volume);
        if (!hasPositiveOhlc(bar)) {
            return null;
        }
        return bar;
    }

    private static boolean hasPositiveOhlc(DailyBar bar) {
        return isPositive(bar.getOpen())
                && isPositive(bar.getHigh())
                && isPositive(bar.getLow())
                && isPositive(bar.getClose());
    }

    private static boolean isPositive(Double value) {
        return value != null && value > 0;
    }

    static String earliestBarDate(List<DailyBar> bars) {
        return bars.stream()
                .map(DailyBar::getDate)
                .filter(date -> date != null && !date.isEmpty())
                .min(String::compareTo)
                .orElse(null);
    }

    static boolean isSupportedSymbol(String symbol) {
        if (symbol == null || symbol.length() < 8) {
            return false;
        }
        String lower = symbol.toLowerCase();
        return lower.startsWith("sh") || lower.startsWith("sz");
    }

    static boolean isIndex(String symbol) {
        if (symbol == null) {
            return false;
        }
        String lower = symbol.toLowerCase();
        return lower.startsWith("sh000") || lower.startsWith("sz399");
    }

    private static Double parseDouble(String str) {
        if (str == null || str.trim().isEmpty() || "-".equals(str.trim())) {
            return null;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
