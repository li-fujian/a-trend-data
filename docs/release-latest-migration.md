# 数据发布方案 — Release `latest`（归档）

> **归档说明（2026-06-07）**：方案已落地。日常操作见 [README.md](../README.md)；Agent 接棒见 [AGENT-HANDOFF.md](../AGENT-HANDOFF.md)。  
> 本文保留为设计决策与审阅记录，非日常运维手册。

---

## 背景

原先 `cache/kline/*.json` 每日 git commit，导致仓库体积大、pull/push 慢。现改为 **GitHub Release 单包分发**。

## 目标架构

```
DataUpdateCli（Step 1–5 写本地）
  → dist/kline-latest.tar.zst
  → gh release upload latest --clobber
  → 删除 tag != latest 的历史 Release

消费方
  → git pull（仅代码）
  → scripts/download-latest-release.* 解压到 cache/
```

**Git 只保留代码；运行时数据通过 Release 分发。**

## 压缩包内容

```
kline-latest.tar.zst
├── cache/kline/
├── config/stock-universe.json
└── logs/fetch-log.json
```

格式：tar + zstd（`tar.zst`）。

## Release 策略

| 项 | 约定 |
|----|------|
| tag | 固定 `latest` |
| 附件 | `kline-latest.tar.zst` |
| 更新 | `gh release upload latest --clobber` |
| 历史 | 上传后删除其它 Release |

## 命令

发布：

```bash
bash scripts/publish-latest-release.sh --repo-root /path/to/a-trend-data
powershell -File scripts/publish-latest-release.ps1 -RepoRoot E:\path\a-trend-data
```

下载：

```bash
bash scripts/download-latest-release.sh --repo-root /path/to/a-trend-data
powershell -File scripts/download-latest-release.ps1 -RepoRoot E:\path\a-trend-data
```

前置：`gh` + `zstd` + `tar`；token 为 `GH_TOKEN` 或 `GITHUB_TOKEN`。

Python 备用（无 zstd CLI / 无 gh）：`package-kline-bundle.py`、`publish-latest-release-api.py`（见 README）。

## DataUpdateCli Step 6

| 旧行为 | 新行为 |
|--------|--------|
| `git add/commit/push` 数据 | 调用 `publish-latest-release` |
| `--no-push` 跳过 git push | `--no-push` 跳过 Release 发布 |

## `.gitignore`

```
/cache/
/config/stock-universe.json
/logs/fetch-log.json
/dist/
/*.tar.zst
```

一次性停止跟踪历史 cache（维护者执行，不自动跑）：

```bash
git rm -r --cached cache/
git commit -m "chore: stop tracking kline cache in git"
```

旧 commit 中的 JSON 仍留在历史中；新数据只走 Release。

## CI

`.github/workflows/fetch-data.yml`：

- 工作日 UTC 07:30 触发 `DataUpdateCli`
- `GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}`，`permissions: contents: write`
- **不再** git push 数据文件

## 实施清单（2026-06-07）

| 项 | 状态 |
|----|------|
| 发布/下载脚本 | ✅ |
| `.gitignore` | ✅ |
| `DataUpdateCli` Step 6 | ✅ |
| `fetch-data.yml` | ✅ |
| README / AGENT-HANDOFF | ✅ |
| `git rm -r --cached cache/` 并提交 | ⏳ 工作区已准备，待维护者 commit |
| 首次 CI 或本机试发 Release | ⏳ 待 `gh` + `zstd` 环境验证 |

## 审阅记录（2026-06-07）

### 结论

| 维度 | 状态 |
|------|------|
| 方案与代码一致 | ✅ |
| `mvn -q test` | ✅ |
| 前复权全量（2026-06-07） | ✅ 3274 OK / 0 failed |
| Git 停止跟踪 cache | ⏳ staged，待 commit |
| 本机 Release 端到端 | ⏳ 部分环境缺 `gh`/`zstd` |

### 数据完整性备注

- `cache/kline/` 文件数可能 **> 股票池**（含 5 指数 + 历史遗留 ETF 等）；打包为全目录快照。
- 抽样 `sh600519`：`adjustment=qfq`，无负 OHLC ✅

### 风险

1. 单附件须 < 2GB（当前约 200–500MB，安全）
2. 只保留 `latest`，无多版本回滚
3. 旧 clone 需 `git pull` 代码 + 下载 Release 获取数据

## 回滚（极端情况）

1. 恢复 `DataUpdateCli` 的 git push 路径
2. 从 `.gitignore` 移除 `cache/`
3. 继续本地 cache，不依赖 Release
