package fetcher;

import com.google.gson.*;
import utils.HttpClientPool;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

/**
 * 从东方财富接口获取全量A股列表，按市值过滤ST，写入 stock-universe.json。
 */
public class StockUniverseFetcher {

    // pz=5000: A股上市公司约5300+，此值需确保覆盖全量；如超出需分页
    private static final String URL =
        "http://push2.eastmoney.com/api/qt/clist/get" +
        "?pn=1&pz=5000&fid=f6" +
        "&fs=m:0+t:6,m:0+t:13,m:1+t:2,m:1+t:23" +
        "&fields=f12,f13,f14,f20";

    // 市值范围：50亿 ~ 5000亿（单位：元）
    static final long MIN_CAP = 5_000_000_000L;
    static final long MAX_CAP = 500_000_000_000L;

    public static class StockEntry {
        public String symbol;
        public String name;
        public long marketCap;

        public StockEntry(String symbol, String name, long marketCap) {
            this.symbol = symbol;
            this.name = name;
            this.marketCap = marketCap;
        }
    }

    /** 市值是否在范围内 */
    public static boolean isInRange(long marketCap) {
        return marketCap >= MIN_CAP && marketCap <= MAX_CAP;
    }

    /** 是否为ST股 */
    public static boolean isST(String name) {
        if (name == null) return false;
        return name.contains("ST");
    }

    /** 根据代码和市场编号构建 symbol */
    public static String buildSymbol(String code, int market) {
        return (market == 1 ? "sh" : "sz") + code;
    }

    /** 构建 StockEntry */
    public static StockEntry parseEntry(String code, int market, String name, long marketCap) {
        return new StockEntry(buildSymbol(code, market), name, marketCap);
    }

    /**
     * 拉取东方财富接口，返回过滤后的股票列表。
     */
    public static List<StockEntry> fetch() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://www.eastmoney.com");
        headers.put("User-Agent", "Mozilla/5.0");

        String json = HttpClientPool.getHttpClient().get(URL, headers);
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Empty response from eastmoney");
        }

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new IOException("Unexpected response: missing 'data' field");
        JsonArray diff = data.getAsJsonArray("diff");
        if (diff == null) throw new IOException("Unexpected response: missing 'data.diff' field");

        List<StockEntry> result = new ArrayList<>();
        for (JsonElement el : diff) {
            try {
                JsonObject obj = el.getAsJsonObject();
                String code = obj.get("f12").getAsString();
                int market = obj.get("f13").getAsInt();
                String name = obj.get("f14").getAsString();
                JsonElement capEl = obj.get("f20");
                if (capEl == null || capEl.isJsonNull()) continue;
                long cap = capEl.getAsLong();

                if (!isST(name) && isInRange(cap)) {
                    result.add(parseEntry(code, market, name, cap));
                }
            } catch (Exception e) {
                System.err.println("Skipping malformed entry: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 将股票列表写入 config/stock-universe.json（覆盖写）。
     */
    public static void save(List<StockEntry> stocks, String outputPath) throws IOException {
        File file = new File(outputPath);
        file.getParentFile().mkdirs();

        JsonObject root = new JsonObject();
        root.addProperty("updated", LocalDate.now().toString());
        root.addProperty("count", stocks.size());

        JsonArray arr = new JsonArray();
        for (StockEntry s : stocks) {
            JsonObject obj = new JsonObject();
            obj.addProperty("symbol", s.symbol);
            obj.addProperty("name", s.name);
            obj.addProperty("market_cap", s.marketCap);
            arr.add(obj);
        }
        root.add("stocks", arr);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(root, writer);
        }
    }

    /**
     * 从 stock-universe.json 读取股票列表。
     */
    public static List<StockEntry> load(String inputPath) throws IOException {
        File file = new File(inputPath);
        if (!file.exists()) {
            throw new FileNotFoundException("stock-universe.json not found: " + inputPath);
        }
        try (FileReader reader = new FileReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("stocks");
            List<StockEntry> result = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                result.add(new StockEntry(
                    obj.get("symbol").getAsString(),
                    obj.get("name").getAsString(),
                    obj.get("market_cap").getAsLong()
                ));
            }
            return result;
        }
    }
}
