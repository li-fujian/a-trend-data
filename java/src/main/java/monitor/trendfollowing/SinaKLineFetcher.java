package monitor.trendfollowing;

import bean.KLineBean;
import cache.DailyBar;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter to fetch K-line data from Sina API and convert to DailyBar format.
 */
public class SinaKLineFetcher {

    private static final int FETCH_DAYS = 700;

    /**
     * Fetch daily bars for a symbol from Sina API.
     * Converts KLineBean list to DailyBar list.
     *
     * @param symbol stock symbol (e.g., "sh600519")
     * @return list of daily bars, or empty list on error
     */
    public static List<DailyBar> fetch(String symbol) {
        List<KLineBean> klineBeans = fetchKLineWithDays(symbol, FETCH_DAYS);

        if (klineBeans == null || klineBeans.isEmpty()) {
            return new ArrayList<>();
        }

        List<DailyBar> dailyBars = new ArrayList<>();
        for (KLineBean bean : klineBeans) {
            DailyBar bar = new DailyBar();
            bar.setDate(bean.getDay());
            bar.setOpen(parseDouble(bean.getOpen()));
            bar.setHigh(parseDouble(bean.getHigh()));
            bar.setLow(parseDouble(bean.getLow()));
            bar.setClose(parseDouble(bean.getClose()));
            bar.setVolume(parseDouble(bean.getVolume()));
            dailyBars.add(bar);
        }

        return dailyBars;
    }

    private static List<KLineBean> fetchKLineWithDays(String symbol, int days) {
        if (!isAShareCode(symbol)) {
            return null;
        }

        String url = "http://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData" +
                "?symbol=" + symbol + "&scale=240&ma=no&datalen=" + days;

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", "http://finance.sina.com.cn");

        try {
            String json = utils.HttpClientPool.getHttpClient().get(url, headers);
            if (json == null || json.trim().isEmpty()) {
                return null;
            }

            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<KLineBean>>() {}.getType();
            List<KLineBean> list = gson.fromJson(json.trim(), type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            System.err.println("Failed to fetch K-line for " + symbol + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isAShareCode(String symbol) {
        return symbol != null &&
                (symbol.toLowerCase().startsWith("sh") || symbol.toLowerCase().startsWith("sz"));
    }

    private static Double parseDouble(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
