# A-Trend Data Pipeline — Agent Handoff Guide

> 面向 AI Agent：如何运行 A 股前复权 K 线每日拉取并发布到 GitHub Release `latest`。  
> 人类读者请优先看 [README.md](README.md)。

---

## 一、你的任务

运行 `DataUpdateCli`，拉取全量 A 股前复权 K 线 + 主要指数，打包并发布 Release `latest`。

**标准命令（Linux / macOS）：**

```bash
cd /path/to/a-trend-data/java
mvn compile -q -DskipTests
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd .. && pwd)"
```

**只拉不发布：** 加 `--no-push`  
**只补深市：** 加 `--only-sz`

完成后检查 `logs/fetch-log.json` 中 `failed` 是否可接受（< 50 通常正常）。

---

## 二、当前状态（2026-06-07）

| 项目 | 状态 |
|------|------|
| 股票池规模 | 约 3274 只（`config/stock-universe.json`，每次 Step 1 刷新） |
| 数据源 | 列表：新浪；K 线：腾讯财经 qfq |
| 最近全量跑批 | 2026-06-07：`3274 OK / 0 failed` |
| 数据分发 | GitHub Release tag `latest`，附件 `kline-latest.tar.zst` |
| Git 跟踪 | `cache/`、`config/stock-universe.json`、`logs/fetch-log.json` 已 `.gitignore`，不再 commit JSON |
| 仓库 | https://github.com/li-fujian/a-trend-data |

**fresh 策略：** 当天已更新且 `adjustment=qfq` 的缓存会被跳过（`isFresh`）。跨天会重新拉取并增量 merge。旧无 `adjustment` 字段的缓存视为 non-fresh，会整包替换为腾讯 qfq。

---

## 三、目录约定（与 a-trend 联调）

```
parent/
├── a-trend/        ← 策略仓库
└── a-trend-data/   ← 本仓库
```

`a-trend` 配置中 `cache_dir` 为 `../a-trend-data/cache/kline`，依赖上述 sibling 布局。

---

## 四、前置条件

### 4.1 仓库与编译

```bash
test -f /path/to/a-trend-data/java/pom.xml
cd /path/to/a-trend-data/java && mvn compile -q -DskipTests
```

### 4.2 a-trend JAR

```bash
ls ~/.m2/repository/com/atrend/a-trend/1.0.0/a-trend-1.0.0.jar
```

缺失时从 sibling `a-trend` 构建并 `mvn install:install-file`（详见 README「开发与依赖」）。

### 4.3 发布工具（Step 6）

```bash
gh auth status    # 本地
# CI 使用 GH_TOKEN / GITHUB_TOKEN
command -v zstd && command -v tar
```

### 4.4 消费方下载数据

无需本地拉取时：

```bash
bash scripts/download-latest-release.sh --repo-root /path/to/a-trend-data
```

---

## 五、运行流程与输出

`DataUpdateCli` 共 6 步：

| Step | 动作 |
|------|------|
| 1 | 新浪拉股票列表 → `config/stock-universe.json` |
| 2 | 5 只主要指数 → `cache/kline/` |
| 3 | 腾讯 qfq 批量拉个股（限速 + 失败补偿） |
| 4 | 指数二次补抓 |
| 5 | 写 `logs/fetch-log.json` |
| 6 | 打包 `dist/kline-latest.tar.zst` 并 `gh release upload latest`（`--no-push` 跳过） |

**正常输出片段：**

```
=== A-Trend Data Update ===
[Step 1] Fetching stock universe from Sina (新浪)...
  [sh_a] 第1页: 100条，累计过滤后 ...
  -> 3274 stocks saved to .../config/stock-universe.json
[Step 2] Fetching daily index benchmarks...
[Step 3] Fetching K-line data for 3274 symbols...
[   1/3274] sh600000     OK
--- batch pause 5000ms ---
=== K-line fetch done: 3274 OK, 0 skipped, 0 failed ===
[Step 5] Writing fetch log...
[Step 6] Publishing GitHub Release latest...
=== Done ===
```

**耗时：** 全量约 45–90 分钟（视网络与 fresh 跳过数量而定）。

**验证：**

```bash
cat /path/to/a-trend-data/logs/fetch-log.json | tail -c 500
ls /path/to/a-trend-data/cache/kline/ | wc -l   # 通常 > 3274（含指数与历史遗留 ETF）
```

---

## 六、常见问题

### 腾讯 K 线限速 / 空响应

**症状：** 连续 FAILED，错误含 `Failed to fetch qfq K-line` 或空响应。

**处理：**

1. Ctrl+C 停止，等待 60s
2. 探测接口：
   ```bash
   curl -s "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?_var=kline_dayqfq&param=sh600519,day,,,3,qfq" | head -c 80
   ```
   返回含 `qfqday` 则可重跑；当天已 fresh 的会自动跳过
3. 若 VPN 干扰 `gtimg.cn`，尝试直连

### stock-universe.json 条数异常（如仅 1 只）

新浪列表接口异常。确认代码最新，`git pull` 后重跑 Step 1。

### Release 发布失败

**原因：** 缺 `gh` / `zstd` / 未登录 / 无 `contents: write` 权限。

**处理：**

```bash
gh auth login
bash scripts/publish-latest-release.sh --repo-root /path/to/a-trend-data
```

Windows 无 `zstd` 时可用 Python 备用（README「从 Release 下载」一节）。

### 编译失败（找不到 utils.HttpClientPool 等）

执行 4.2 安装 `a-trend` JAR。

---

## 七、文件结构

```
a-trend-data/
├── java/
│   ├── pom.xml
│   └── src/main/java/
│       ├── DataUpdateCli.java          # 主入口
│       ├── FetchIndicesCli.java        # 指数拉取
│       ├── fetcher/
│       │   ├── StockUniverseFetcher.java   # 新浪股票池
│       │   └── BulkKLineFetcher.java       # 批量 K 线（限速/重试）
│       ├── monitor/trendfollowing/
│       │   ├── TencentQfqKLineFetcher.java      # 生产默认
│       │   └── EastmoneyQfqKLineFetcher.java    # @Deprecated 备用
│       └── log/FetchLog.java
├── scripts/
│   ├── publish-latest-release.{sh,ps1}
│   ├── download-latest-release.{sh,ps1}
│   ├── package-kline-bundle.py         # 无 zstd CLI 时打包
│   └── publish-latest-release-api.py   # 无 gh 时 REST 发布
├── cache/kline/                        # gitignored
├── config/stock-universe.json          # gitignored
└── logs/fetch-log.json                 # gitignored
```

---

## 八、可调参数

限速常量位于 `BulkKLineFetcher.java`：

```java
private static final int MIN_SLEEP_MS = 2200;
private static final int MAX_SLEEP_MS = 3800;
private static final int BATCH_PAUSE_MS = 5000;  // 每 50 只
```

修改后 `mvn compile -q -DskipTests` 再运行。

---

## 九、一键脚本（复制执行）

```bash
REPO=/path/to/a-trend-data
JAR=~/.m2/repository/com/atrend/a-trend/1.0.0/a-trend-1.0.0.jar

test -f "$JAR" || { echo "ERROR: a-trend JAR missing"; exit 1; }
cd "$REPO/java"
mvn compile -q -DskipTests || exit 1
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $REPO"

echo "Cache files: $(ls "$REPO/cache/kline" | wc -l)"
tail -n 20 "$REPO/logs/fetch-log.json"
```
