package monitor.trendfollowing;

import cache.DailyBar;
import cache.VolumeUnit;
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
 * Each bar is {@code [date, open, close, high, low, volume]}. Cache volume is always shares.
 * Tencent returns lots (手) for main board / ChiNext / B-shares, and shares for STAR
 * ({@code sh688}/{@code sh689}) and BSE. Scaling is detected from the quote's
 * volume+amount when present, otherwise from the board prefix.
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
        Boolean scaleVolume = null;

        while (byDate.size() < target) {
            int requestSize = Math.min(chunk, target - byDate.size());
            ParsedChunk parsed = fetchChunk(symbol, "", endDate, requestSize, scaleVolume);
            List<DailyBar> chunkBars = parsed.bars;
            if (scaleVolume == null && parsed.scaleVolume != null) {
                scaleVolume = parsed.scaleVolume;
            }
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

    private static ParsedChunk fetchChunk(String symbol, String startDate, String endDate,
                                          int count, Boolean scaleOverride) {
        String url = String.format(URL, symbol, startDate, endDate, count);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://finance.qq.com/");

        try {
            String body = utils.HttpClientPool.getHttpClient().get(url, headers);
            return parseResponse(symbol, body, scaleOverride);
        } catch (Exception e) {
            System.err.println("Failed to fetch qfq K-line for " + symbol + ": " + e.getMessage());
            return ParsedChunk.empty();
        }
    }

    static List<DailyBar> parseResponse(String symbol, String body) {
        return parseResponse(symbol, body, null).bars;
    }

    static ParsedChunk parseResponse(String symbol, String body, Boolean scaleOverride) {
        List<DailyBar> bars = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return new ParsedChunk(bars, resolveScale(symbol, null, scaleOverride));
        }

        String json = body.trim();
        int eq = json.indexOf('=');
        if (eq >= 0) {
            json = json.substring(eq + 1);
        }

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        if (root == null || !root.has("data")) {
            return new ParsedChunk(bars, resolveScale(symbol, null, scaleOverride));
        }

        JsonElement dataElem = root.get("data");
        if (dataElem == null || dataElem.isJsonArray()) {
            return new ParsedChunk(bars, resolveScale(symbol, null, scaleOverride));
        }

        JsonObject data = dataElem.getAsJsonObject();
        if (!data.has(symbol) || !data.get(symbol).isJsonObject()) {
            return new ParsedChunk(bars, resolveScale(symbol, null, scaleOverride));
        }

        JsonObject symbolData = data.getAsJsonObject(symbol);
        JsonArray rows = null;
        if (symbolData.has("qfqday")) {
            rows = symbolData.getAsJsonArray("qfqday");
        } else if (symbolData.has("day")) {
            rows = symbolData.getAsJsonArray("day");
        }

        boolean scaleVolume = resolveScale(symbol, symbolData, scaleOverride);
        if (rows == null) {
            return new ParsedChunk(bars, scaleVolume);
        }

        for (JsonElement row : rows) {
            DailyBar bar = parseBar(row, scaleVolume);
            if (bar != null) {
                bars.add(bar);
            }
        }
        return new ParsedChunk(bars, scaleVolume);
    }

    static boolean resolveScale(String symbol, JsonObject symbolData, Boolean scaleOverride) {
        if (VolumeUnit.isIndex(symbol)) {
            return false;
        }
        if (scaleOverride != null) {
            return scaleOverride;
        }
        Boolean detected = detectScaleFromQt(symbol, symbolData);
        if (detected != null) {
            return detected;
        }
        return VolumeUnit.shouldScaleLotsToShares(symbol);
    }

    static Boolean detectScaleFromQt(String symbol, JsonObject symbolData) {
        if (symbolData == null || !symbolData.has("qt") || !symbolData.get("qt").isJsonObject()) {
            return null;
        }
        JsonObject qt = symbolData.getAsJsonObject("qt");
        if (!qt.has(symbol) || !qt.get(symbol).isJsonArray()) {
            return null;
        }
        JsonArray fields = qt.getAsJsonArray(symbol);
        Double price = fields.size() > 3 ? parseDouble(jsonString(fields.get(3))) : null;
        Double volume = null;
        Double amount = null;
        for (int i = 0; i < fields.size(); i++) {
            String raw = jsonString(fields.get(i));
            if (raw == null) {
                continue;
            }
            int first = raw.indexOf('/');
            int last = raw.lastIndexOf('/');
            if (first <= 0 || last <= first) {
                continue;
            }
            String[] parts = raw.split("/");
            if (parts.length != 3) {
                continue;
            }
            Double p = parseDouble(parts[0]);
            Double v = parseDouble(parts[1]);
            Double a = parseDouble(parts[2]);
            if (p == null || v == null || a == null) {
                continue;
            }
            if (v > 0 && a > v) {
                if (price == null) {
                    price = p;
                }
                volume = v;
                amount = a;
                break;
            }
        }
        return VolumeUnit.detectScaleFromQuote(price, volume, amount);
    }

    private static String jsonString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (Exception e) {
            return element.toString();
        }
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
        return VolumeUnit.isIndex(symbol);
    }

    static final class ParsedChunk {
        final List<DailyBar> bars;
        final Boolean scaleVolume;

        ParsedChunk(List<DailyBar> bars, Boolean scaleVolume) {
            this.bars = bars != null ? bars : new ArrayList<DailyBar>();
            this.scaleVolume = scaleVolume;
        }

        static ParsedChunk empty() {
            return new ParsedChunk(new ArrayList<DailyBar>(), null);
        }
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
