# a-trend-data

A股全量前复权 K 线数据管道，每日自动拉取并推送到 GitHub。

## 结构

- `cache/kline/` — 前复权 K 线缓存，每只股票一个 JSON 文件
- `config/stock-universe.json` — 全量股票列表（市值50亿-5000亿，排除ST）
- `logs/fetch-log.json` — 每日拉取日志
- `java/` — Java 实现（本地运行）
- `python/` — Python 实现（云服务器，待实现）

## 运行（Java）

```bash
cd java
mvn compile -q -DskipTests
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd ../.. && pwd)"
```

## 数据口径

- K 线数据：东方财富历史 K 线接口，`fqt=1`，即前复权。
- 股票列表：新浪 A 股列表接口，按市值 50 亿-5000 亿过滤并排除 ST。
- 缓存 JSON 写入 `adjustment: "qfq"`；旧的未标记缓存会被视为非 fresh，下一次运行会自动重刷为前复权口径。
- 成交量字段保持历史缓存单位：股。东方财富返回成交量单位为手，写缓存时会乘以 100。
