# a-trend-data

A股全量K线数据管道，每日自动拉取并推送到 GitHub。

## 结构

- `cache/kline/` — K线缓存，每只股票一个 JSON 文件
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

