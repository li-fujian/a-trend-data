package monitor.trendfollowing;

import cache.DailyBar;
import com.google.gson.Gson;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches daily A-share K-lines from Eastmoney using forward-adjusted prices.
 *
 * <p>Eastmoney parameter {@code fqt=1} is 前复权. The kline payload is a comma-separated
 * string:
 *
 * <pre>
 * date,open,close,high,low,volume,amount,amplitude,pct_chg,chg,turnover
 * </pre>
 *
 * <p>Eastmoney volume is in hands. Cache historically stores Sina volume in shares, so this
 * adapter multiplies Eastmoney volume by 100 to keep downstream volume ratios and units stable.
 */
public class EastmoneyQfqKLineFetcher {

    /** Large enough for full A-share history in the current research window. */
    public static final int MAX_DATALEN = 5000;

    private static final String URL =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                    + "?secid=%s"
                    + "&fields1=f1,f2,f3,f4,f5,f6"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                    + "&klt=101"
                    + "&fqt=1"
                    + "&beg=0"
                    + "&end=20500101"
                    + "&lmt=%d";

    public static List<DailyBar> fetch(String symbol) {
        return fetch(symbol, MAX_DATALEN);
    }

    public static List<DailyBar> fetch(String symbol, int datalen) {
        String secid = toSecid(symbol);
        if (secid == null) {
            return new ArrayList<>();
        }

        int n = Math.max(1, Math.min(datalen, MAX_DATALEN));
        String url = String.format(URL, urlEncode(secid), n);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://quote.eastmoney.com/");

        try {
            String json = utils.HttpClientPool.getHttpClient().get(url, headers);
            if (json == null || json.trim().isEmpty()) {
                return new ArrayList<>();
            }

            Gson gson = new Gson();
            EastmoneyResponse response = gson.fromJson(json.trim(), EastmoneyResponse.class);
            if (response == null || response.data == null || response.data.klines == null) {
                return new ArrayList<>();
            }

            List<DailyBar> dailyBars = new ArrayList<>();
            for (String line : response.data.klines) {
                DailyBar bar = parseKline(line);
                if (bar != null) {
                    dailyBars.add(bar);
                }
            }
            return dailyBars;
        } catch (Exception e) {
            System.err.println("Failed to fetch qfq K-line for " + symbol + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    static String toSecid(String symbol) {
        if (symbol == null || symbol.length() < 8) {
            return null;
        }
        String lower = symbol.toLowerCase();
        String code = lower.substring(2);
        if (lower.startsWith("sh")) {
            return "1." + code;
        }
        if (lower.startsWith("sz")) {
            return "0." + code;
        }
        return null;
    }

    static DailyBar parseKline(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split(",");
        if (parts.length < 6) {
            return null;
        }

        DailyBar bar = new DailyBar();
        bar.setDate(parts[0]);
        bar.setOpen(parseDouble(parts[1]));
        bar.setClose(parseDouble(parts[2]));
        bar.setHigh(parseDouble(parts[3]));
        bar.setLow(parseDouble(parts[4]));

        Double hands = parseDouble(parts[5]);
        bar.setVolume(hands == null ? null : hands * 100.0);
        return bar;
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

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    static class EastmoneyResponse {
        EastmoneyData data;
    }

    static class EastmoneyData {
        List<String> klines;
    }
}
