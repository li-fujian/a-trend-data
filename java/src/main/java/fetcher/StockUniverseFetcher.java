package fetcher;

import com.google.gson.*;
import utils.HttpClientPool;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

/**
 * 从新浪财经接口获取全量A股列表，按市值过滤ST，写入 stock-universe.json。
 * 使用新浪 Market_Center.getHQNodeData 接口，分别拉取 sh_a 和 sz_a，每页100条。
 * mktcap 字段单位为万元。
 */
public class StockUniverseFetcher {

    // 新浪股票列表接口，%s=node(sh_a/sz_a), %d=page
    private static final String URL_TEMPLATE =
        "http://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php" +
        "/Market_Center.getHQNodeData?page=%d&num=100&sort=symbol&asc=1&node=%s";

    // 市值范围：50亿 ~ 5000亿（单位：万元，50亿=50_0000万，5000亿=5000_0000万）
    static final long MIN_CAP_WAN = 50_0000L;   // 50亿（万元）
    static final long MAX_CAP_WAN = 15000_0000L; // 15000亿（万元）

    // 兼容单位为元的旧接口（测试用）
    static final long MIN_CAP = 5_000_000_000L;
    static final long MAX_CAP = 1_500_000_000_000L;
    private static final int MAX_CONSECUTIVE_PARSE_ERRORS = 3;

    public static class StockEntry {
        public String symbol;
        public String name;
        public long marketCap; // 单位：元

        public StockEntry(String symbol, String name, long marketCap) {
            this.symbol = symbol;
            this.name = name;
            this.marketCap = marketCap;
        }
    }

    /** 市值是否在范围内（单位：元） */
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
     * 拉取新浪接口（分页），返回过滤后的股票列表。
     * 分别拉取 sh_a（上海A股）和 sz_a（深圳A股），每页100条，直到空页为止。
     */
    public static List<StockEntry> fetch() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://finance.sina.com.cn");
        headers.put("User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

        List<StockEntry> result = new ArrayList<>();
        for (String node : new String[]{"sh_a", "sz_a"}) {
            int page = 1;
            int nodeTotal = 0;
            int parseErrors = 0;
            while (true) {
                String url = String.format(URL_TEMPLATE, page, node);
                String json = HttpClientPool.getHttpClient().get(url, headers);
                if (json == null || json.trim().isEmpty() || json.trim().equals("[]")) break;

                JsonArray arr;
                try {
                    com.google.gson.stream.JsonReader jr =
                        new com.google.gson.stream.JsonReader(new java.io.StringReader(json.trim()));
                    jr.setLenient(true);
                    arr = JsonParser.parseReader(jr).getAsJsonArray();
                } catch (Exception e) {
                    System.err.println("Parse error on " + node + " page " + page + ": " + e.getMessage());
                    parseErrors++;
                    if (parseErrors >= MAX_CONSECUTIVE_PARSE_ERRORS) {
                        throw new IOException("Sina stock universe returned invalid data for "
                            + node + " after " + parseErrors + " consecutive pages");
                    }
                    page++;
                    continue;
                }
                parseErrors = 0;
                if (arr.size() == 0) break;

                for (JsonElement el : arr) {
                    try {
                        JsonObject obj = el.getAsJsonObject();
                        String symbol = obj.get("symbol").getAsString(); // e.g. "sh600000"
                        String name = obj.get("name").getAsString();
                        // mktcap 单位万元，转换为元
                        JsonElement capEl = obj.get("mktcap");
                        if (capEl == null || capEl.isJsonNull()) continue;
                        double mktcapWan = capEl.getAsDouble();
                        long marketCapYuan = (long)(mktcapWan * 10000);

                        if (!isST(name) && isInRange(marketCapYuan)) {
                            result.add(new StockEntry(symbol, name, marketCapYuan));
                            nodeTotal++;
                        }
                    } catch (Exception e) {
                        System.err.println("Skipping malformed entry: " + e.getMessage());
                    }
                }

                System.out.printf("  [%s] 第%d页: %d条，累计过滤后 %d 只%n",
                    node, page, arr.size(), result.size());
                page++;

                // 页间停顿 200ms
                try { Thread.sleep(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.printf("  [%s] 共 %d 只符合条件%n", node, nodeTotal);
        }
        return result;
    }

    /**
     * 将股票列表写入 config/stock-universe.json（覆盖写）。
     */
    public static void save(List<StockEntry> stocks, String outputPath) throws IOException {
        File file = new File(outputPath);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

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
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
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
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
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
