# A-Trend Data Pipeline — Agent Handoff Guide

> 本文档面向 AI Agent 阅读。描述如何接管 A 股 K 线数据的每日拉取任务。

---

## 一、你的任务

运行数据更新程序，拉取全量 A 股前复权 K 线数据，推送到 GitHub。

**一句话流程：**
```bash
cd ~/cursorProjects/a-trend-data/java
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd .. && pwd)"
```

运行完成后会自动 git push，不需要手动操作。

---

## 二、当前状态（截至 2026-03-31）

| 项目 | 状态 |
|------|------|
| 总股票数（stock-universe.json） | 3282 只 |
| 已拉取 K 线缓存 | 约 600 只（sh600000 ~ sh601665 范围） |
| 未拉取 | 约 2682 只（sh601xxx 后半段 + 全部 sz 开头） |
| GitHub 仓库 | https://github.com/li-fujian/a-trend-data |

**断点续跑说明：** 程序会自动跳过当天已更新且 `adjustment=qfq` 的缓存（`isFresh` 检查）。**但跨天不会跳过**——第二天运行会重新拉取所有股票（已有缓存会做增量合并，不会丢数据，只是多花时间）。旧的未复权缓存没有 `adjustment` 字段，会被视为非 fresh，下一次运行会自动重刷为前复权。

---

## 三、目录约定（重要）

本仓库（`a-trend-data`）和策略仓库（`a-trend`）**必须放在同一父目录下**：

```
~/any-parent-dir/
  ├── a-trend/        ← 策略仓库
  └── a-trend-data/   ← 本仓库（数据仓库）
```

`a-trend` 的配置文件中 `cache_dir` 写的是 `../a-trend-data/cache/kline`，依赖这个相对路径。如果目录结构不对，策略仓库读不到数据。

---

## 四、前置条件检查

运行前，请依次确认以下几点：

### 3.1 目录存在
```bash
ls ~/cursorProjects/a-trend-data/java/pom.xml
```
如果文件不存在，需要先 clone 仓库：
```bash
cd ~/cursorProjects
git clone git@github.com:li-fujian/a-trend-data.git
```

### 3.2 依赖 JAR 存在
```bash
ls ~/.m2/repository/com/atrend/a-trend/1.0.0/a-trend-1.0.0.jar
```
如果不存在，需要从 a-trend 项目安装：
```bash
cd ~/cursorProjects/a-trend
mvn package -q -DskipTests
mvn install:install-file \
  -Dfile=target/a-trend-1.0.0.jar \
  -DgroupId=com.atrend \
  -DartifactId=a-trend \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```
如果 `~/cursorProjects/a-trend` 目录不存在：
```bash
cd ~/cursorProjects
git clone git@github.com:li-fujian/a-trend.git
cd a-trend
mvn package -q -DskipTests
mvn install:install-file \
  -Dfile=target/a-trend-1.0.0.jar \
  -DgroupId=com.atrend \
  -DartifactId=a-trend \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

### 3.3 git push 权限
```bash
ssh -T git@github.com
```
应该输出：`Hi li-fujian! You've successfully authenticated`

如果失败，说明 SSH key 未配置，需要用户手动处理。

### 3.4 编译
```bash
cd ~/cursorProjects/a-trend-data/java
mvn compile -q -DskipTests
```
应该无输出（成功）。如果报错，停止并报告错误。

---

## 五、运行程序

### 4.1 正式运行（约 45-90 分钟）

```bash
cd ~/cursorProjects/a-trend-data/java
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd .. && pwd)"
```

**正常输出示例：**
```
=== A-Trend Data Update ===
Repo root: /Users/xxx/cursorProjects/a-trend-data

[Step 1] Fetching stock universe from eastmoney...
  [sh_a] 第1页: 100条，累计过滤后 87 只
  ...
  -> 3282 stocks saved to .../config/stock-universe.json

[Step 2] Fetching K-line data for 3282 symbols...
[   1/3282] sh600000     OK
[   2/3282] sh600004     OK
...
--- batch pause 5000ms ---
...
=== K-line fetch done: 3282 OK, 0 skipped, 0 failed ===

[Step 3] Writing fetch log...
[Step 4] Git commit and push...
=== Done ===
```

### 4.2 判断是否成功

程序结束后检查：
```bash
# 查看日志，确认 failed 数量
cat ~/cursorProjects/a-trend-data/logs/fetch-log.json

# 查看缓存文件数量（应该接近 3282）
ls ~/cursorProjects/a-trend-data/cache/kline/ | wc -l
```

成功标准：`failed` 字段 < 50（少量失败是正常的，东方财富偶发限速或空响应）。

---

## 六、常见问题处理

### 问题 1：东方财富 K 线接口返回空响应/拒绝访问（限速）

**症状：** 大量连续 FAILED，错误包含 `Failed to fetch qfq K-line`、空响应或连接关闭。

**原因：** 请求频率过高，东方财富临时限制当前出口，通常等待一段时间后恢复。

**处理：**
1. 停止程序（Ctrl+C）
2. 等待 60 秒
3. 测试接口是否恢复：
   ```bash
   curl -L --compressed -sS -A "Mozilla/5.0" -e "https://quote.eastmoney.com/" \
     "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600519&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&klt=101&fqt=1&beg=0&end=20500101&lmt=3" | head -c 80
   ```
   如果返回包含 `"klines"` 的 JSON，说明已恢复，可以重新运行。
4. 重新运行程序（当天运行会跳过已拉取的，从断点继续）

### 问题 2：stock-universe.json 只有 1 只股票

**原因：** 新浪股票列表接口被封或返回异常。

**处理：** 确认使用的是最新代码（git pull），然后重新运行。

### 问题 3：git push 失败

**症状：** `[Step 4]` 报错 `git command failed`

**原因：** SSH key 未配置或网络问题。

**处理：** 让用户检查 SSH 配置，或手动 push：
```bash
cd ~/cursorProjects/a-trend-data
git push
```

### 问题 4：编译失败（找不到 utils.HttpClientPool 等类）

**原因：** a-trend JAR 未安装到本地 Maven 仓库。

**处理：** 执行 3.2 节的安装步骤。

---

## 七、文件结构说明

```
a-trend-data/
├── java/                        # Java 实现（本机运行）
│   ├── pom.xml                  # Maven 配置
│   └── src/main/java/
│       ├── DataUpdateCli.java   # 主入口（你只需要运行这个）
│       ├── fetcher/
│       │   ├── StockUniverseFetcher.java  # 拉股票列表（新浪）
│       │   └── BulkKLineFetcher.java      # 批量拉K线（东方财富前复权）
│       ├── monitor/trendfollowing/
│       │   └── EastmoneyQfqKLineFetcher.java # 东方财富前复权日线
│       └── log/
│           └── FetchLog.java    # 写日志
├── cache/kline/                 # 前复权K线缓存，每只股票一个 JSON 文件
├── config/
│   └── stock-universe.json      # 全量股票列表（每次运行自动更新）
└── logs/
    └── fetch-log.json           # 每日拉取日志
```

---

## 八、关键参数（如需调整）

如果频繁被限速，可以修改 `BulkKLineFetcher.java` 中的常量：

```java
// 当前值（较保守）
private static final int MIN_SLEEP_MS = 1500;   // 每次请求后最小等待
private static final int MAX_SLEEP_MS = 2500;   // 每次请求后最大等待
private static final int BATCH_PAUSE_MS = 5000; // 每50只后额外等待
```

修改后需要重新编译：
```bash
cd ~/cursorProjects/a-trend-data/java
mvn compile -q -DskipTests
```

---

## 九、完整运行脚本（复制可直接执行）

```bash
# 前置检查
ls ~/.m2/repository/com/atrend/a-trend/1.0.0/a-trend-1.0.0.jar || echo "ERROR: JAR missing"
ssh -T git@github.com 2>&1 | grep "successfully" || echo "ERROR: SSH auth failed"

# 编译
cd ~/cursorProjects/a-trend-data/java
mvn compile -q -DskipTests || { echo "ERROR: compile failed"; exit 1; }

# 运行
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd .. && pwd)"

# 验证
echo "Cache files: $(ls ../cache/kline/ | wc -l)"
cat ../logs/fetch-log.json
```
