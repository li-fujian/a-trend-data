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

    // 接口每页固定返回100条，pz参数无效，需分页拉取
    private static final String URL_TEMPLATE =
        "http://push2.eastmoney.com/api/qt/clist/get" +
        "?pn=%d&pz=100&fid=f6" +
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
     * 拉取东方财富接口（分页），返回过滤后的股票列表。
     * 接口每页固定返回100条，通过 pn 参数翻页直到无数据为止。
     */
    public static List<StockEntry> fetch() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://quote.eastmoney.com/");
        headers.put("Origin", "http://quote.eastmoney.com");
        headers.put("User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        List<StockEntry> result = new ArrayList<>();
        int page = 1;
        int total = Integer.MAX_VALUE; // 第一页后更新为实际总数

        while ((page - 1) * 100 < total) {
            String url = String.format(URL_TEMPLATE, page);
            String json = HttpClientPool.getHttpClient().get(url, headers);
            if (json == null || json.trim().isEmpty()) {
                throw new IOException("Empty response from eastmoney on page " + page);
            }

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            if (data == null) throw new IOException("Unexpected response: missing 'data' field");

            // 第一页获取总数
            if (page == 1) {
                JsonElement totalEl = data.get("total");
                if (totalEl != null && !totalEl.isJsonNull()) {
                    total = totalEl.getAsInt();
                    System.out.println("  东方财富总股票数: " + total + "，预计分 " + (int)Math.ceil(total / 100.0) + " 页");
                }
            }

            // diff 可能是 JsonArray 也可能是 JsonObject（键为 "0","1","2",...）
            JsonElement diffEl = data.get("diff");
            if (diffEl == null || diffEl.isJsonNull()) break;

            Iterable<JsonElement> items;
            int pageSize;
            if (diffEl.isJsonArray()) {
                JsonArray arr = diffEl.getAsJsonArray();
                items = arr;
                pageSize = arr.size();
            } else {
                java.util.Collection<JsonElement> vals = diffEl.getAsJsonObject().entrySet().stream()
                    .map(Map.Entry::getValue)
                    .collect(java.util.stream.Collectors.toList());
                items = vals;
                pageSize = vals.size();
            }

            if (pageSize == 0) break;

            for (JsonElement el : items) {
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

            System.out.printf("  第 %d 页: 获取 %d 条，累计过滤后 %d 只%n", page, pageSize, result.size());
            page++;

            // 页间停顿 200ms，避免请求过快
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
