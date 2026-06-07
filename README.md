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
| `dist/kline-latest.tar.zst` | 发布前临时压缩包 | ❌ |
| `.github/workflows/fetch-data.yml` | 定时 CI 拉取 + 发布 | ✅ |

---

## 数据拉取

```bash
cd java
mvn compile -q -DskipTests
mvn -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root $(cd .. && pwd)"
```

常用参数：

| 参数 | 含义 |
|------|------|
| `--repo-root PATH` | 仓库根目录（默认 `java/` 的上一级） |
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

**CI**：`.github/workflows/fetch-data.yml` 工作日 UTC 07:30（北京时间 15:30）运行 `DataUpdateCli`，通过 `GH_TOKEN` 发布 Release，**不再 git push 数据文件**。

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
