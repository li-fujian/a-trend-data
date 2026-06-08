# a-trend-data

A 股前复权 K 线数据管道：每日拉取全市场数据，打包为 `kline-latest.tar.zst`，发布到 GitHub Release `latest`。Git 仓库只保留代码与脚本，**不再提交数千个 JSON 缓存文件**。

仓库：https://github.com/li-fujian/a-trend-data

---

## 快速开始

### 1. 克隆代码

```bash
git clone https://github.com/li-fujian/a-trend-data.git
cd a-trend-data
```

### 2. 获取数据（二选一）

**方式 A — 下载最新 Release（推荐，无需拉取）**

```bash
# Linux / macOS / Git Bash
bash scripts/download-latest-release.sh --repo-root /path/to/a-trend-data
```

```powershell
# Windows
powershell -File scripts/download-latest-release.ps1 -RepoRoot E:\code\a-trend-data
```

前置：`gh`（[GitHub CLI](https://cli.github.com/)）、`zstd`。Windows 10+ 自带 `tar`。

**方式 B — 本地拉取并发布**

见下方 [数据拉取](#数据拉取)。需 JDK 8、Maven，以及 sibling 项目 `a-trend` 的 JAR（见 [开发与依赖](#开发与依赖)）。

### 3. 下游策略仓库

若使用 [a-trend](https://github.com/li-fujian/a-trend) 策略项目，请将两个仓库放在同一父目录：

```
parent/
├── a-trend/        # 策略仓库，cache_dir 指向 ../a-trend-data/cache/kline
└── a-trend-data/   # 本仓库
```

---

## 目录结构

| 路径 | 说明 | 是否进 Git |
|------|------|------------|
| `java/` | Java 拉取与打包入口 | ✅ |
| `scripts/` | 发布/下载脚本（sh、ps1、Python 备用） | ✅ |
| `cache/kline/` | 每只标的一个 JSON，前复权日线 | ❌（Release 分发） |
| `config/stock-universe.json` | 全量股票池（市值过滤后） | ❌ |
| `logs/fetch-log.json` | 每次拉取摘要 | ❌ |
| `logs/cron/` | 云端定时任务日志 | ❌ |
| `dist/kline-latest.tar.zst` | 发布前临时压缩包 | ❌ |

---

## 数据拉取

```bash
cd java
mvn compile -q -DskipTests
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd .. && pwd) --mode incremental"
```

常用参数：

| 参数 | 含义 |
|------|------|
| `--repo-root PATH` | 仓库根目录（默认 `java/` 的上一级） |
| `--mode incremental` | 默认模式，只抓每只标的最近一段日 K，并与本地缓存 merge |
| `--mode full` | 全量重建模式，抓腾讯可返回的最长历史 |
| `--incremental-bars N` | 增量模式每只标的抓最近 N 根日 K，默认 420 |
| `--max-failed-to-publish N` | 失败数超过 N 时不覆盖 Release，默认 20 |
| `--min-fresh-to-publish N` | 当天成功更新为今日日期的标的少于 N 时不覆盖 Release，默认 1000 |
| `--trading-days-only` | 周末直接跳过；法定节假日建议由 cron 或外部日历控制 |
| `--no-push` | 跳过 Step 6，不发布 Release |
| `--only-sz` | 仅拉取深市个股（调试/补跑） |

本地只拉不发布：

```bash
"-Dexec.args=--repo-root /path/to/a-trend-data --no-push"
```

单独补抓指数：

```bash
mvn -q exec:java -Dexec.mainClass=FetchIndicesCli \
    "-Dexec.args=--repo-root /path/to/a-trend-data"
```

发布 Step 6 需要 `gh auth login`（或环境变量 `GH_TOKEN` / `GITHUB_TOKEN`）以及 `zstd`、`tar`。

---

## 腾讯云定时更新

推荐在腾讯云 CVM 上持久保存 `cache/kline/`，每个交易日收盘后运行增量更新，成功后覆盖 GitHub Release `latest`。

一次性准备：

```bash
sudo apt-get update
sudo apt-get install -y git maven openjdk-8-jdk gh zstd tar
gh auth login
git clone https://github.com/li-fujian/a-trend-data.git /opt/a-trend-data
```

手动试跑：

```bash
cd /opt/a-trend-data
bash scripts/daily_fetch.sh
```

cron 示例（北京时间工作日 17:00 运行）：

```cron
0 17 * * 1-5 A_TREND_DATA_REPO_ROOT=/opt/a-trend-data /bin/bash /opt/a-trend-data/scripts/daily_fetch.sh
```

可调环境变量：

| 变量 | 默认值 | 含义 |
|------|--------|------|
| `A_TREND_FETCH_MODE` | `incremental` | `incremental` 或 `full` |
| `A_TREND_INCREMENTAL_BARS` | `420` | 增量模式每只标的抓取的最近日 K 数 |
| `A_TREND_MAX_FAILED_TO_PUBLISH` | `20` | 超过该失败数就不上传 Release |
| `A_TREND_MIN_FRESH_TO_PUBLISH` | `1000` | 当天 fresh 标的少于该数量就不上传 Release |
| `A_TREND_MIN_SLEEP_MS` / `A_TREND_MAX_SLEEP_MS` | `1200` / `2200` | 个股请求间随机等待 |
| `A_TREND_NOTIFY_WEBHOOK` | 空 | 可选，任务成功/失败后 POST JSON 通知 |

脚本会使用 `.daily-fetch.lock` 防止重复运行，并把日志写到 `logs/cron/daily-fetch-YYYY-MM-DD.log`。

---

## 从 Release 下载

- **Release tag**：固定 `latest`
- **附件**：`kline-latest.tar.zst`（含 `cache/kline`、`config/stock-universe.json`、`logs/fetch-log.json`）
- 每次发布覆盖附件，并删除其它历史 Release

手动发布（拉取完成后也会自动调用）：

```bash
bash scripts/publish-latest-release.sh --repo-root /path/to/a-trend-data
```

```powershell
powershell -File scripts/publish-latest-release.ps1 -RepoRoot E:\code\a-trend-data
```

**无 `zstd` CLI 时的 Python 备用**（需 `pip install zstandard`）：

```bash
python scripts/package-kline-bundle.py --repo-root /path/to/a-trend-data
GITHUB_TOKEN=xxx python scripts/publish-latest-release-api.py --repo-root /path/to/a-trend-data
```

---

## 开发与依赖

### 环境

- JDK 8、Maven 3.x
- 可选：`gh`、`zstd`（发布/下载 Release）
- 测试：`cd java && mvn -q test`

### a-trend JAR

编译依赖 sibling 项目 `a-trend` 安装到本地 Maven：

```bash
cd ../a-trend   # 与 a-trend-data 同级
mvn package -q -DskipTests
mvn install:install-file \
  -Dfile=target/a-trend-1.0.0.jar \
  -DgroupId=com.atrend -DartifactId=a-trend \
  -Dversion=1.0.0 -Dpackaging=jar
```

验证：`~/.m2/repository/com/atrend/a-trend/1.0.0/a-trend-1.0.0.jar` 存在。

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│  DataUpdateCli（主入口）                                     │
├─────────────────────────────────────────────────────────────┤
│  Step 1  StockUniverseFetcher  → 新浪 A 股列表，市值过滤     │
│  Step 2  FetchIndicesCli       → 5 只主要指数               │
│  Step 3  BulkKLineFetcher      → 腾讯财经 qfq 个股批量拉取   │
│  Step 4  FetchIndicesCli       → 指数二次补抓               │
│  Step 5  FetchLog              → logs/fetch-log.json        │
│  Step 6  publish-latest-release → dist/kline-latest.tar.zst │
│          gh release upload latest --clobber                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
              GitHub Release `latest` / kline-latest.tar.zst
                              │
                              ▼
              download-latest-release.* → 解压到 cache/
```

**数据源**

| 数据 | 接口 | 说明 |
|------|------|------|
| 股票列表 | 新浪 `Market_Center.getHQNodeData` | 市值 50 亿–1.5 万亿，排除 ST |
| 个股 K 线 | 腾讯 `web.ifzq.gtimg.cn` | `qfq` 前复权；成交量手→股 ×100 |
| 指数 K 线 | 同上 | 5 只：上证、沪深300、中证500、科创50、创业板指 |

缓存 JSON 字段 `adjustment: "qfq"`。无该字段的旧缓存视为 non-fresh，下次运行整包替换。

**定时任务**：GitHub Actions 不再运行数据更新。生产建议使用腾讯云 CVM 上的 `scripts/daily_fetch.sh`，通过 cron 在收盘后增量更新并发布 Release。

---

## 数据口径与限制

- 前复权（qfq）；腾讯早期段可能存在脏数据，解析时会丢弃 OHLC ≤ 0 的 bar，部分标的有效历史晚于上市日（如茅台约自 2016-06 起）。
- 北交所（`bj` 前缀）不在股票池内。
- `cache/kline/` 可能含历史遗留 ETF 等额外 JSON；打包为**全目录快照**，不限于 `stock-universe.json` 内标的。
- 2026-06-07 全量拉取参考：`total=3274`，`ok=3274`，`failed=0`（见 `logs/fetch-log.json`）。

---

## 更多文档

| 文档 | 用途 |
|------|------|
| [AGENT-HANDOFF.md](AGENT-HANDOFF.md) | AI Agent 接棒运行指南 |
