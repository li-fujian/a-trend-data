# 腾讯 K 线迁移 — 审阅记录（归档）

> **归档说明（2026-06-07）**：东方财富 → 腾讯财经前复权迁移已完成并验收。  
> 当前运维与数据源说明见 [README.md](../README.md)、[AGENT-HANDOFF.md](../AGENT-HANDOFF.md)。  
> 本文保留为历史审阅与处置记录。

---

## 状态总览

| # | 原问题 | 优先级 | 状态 |
|---|--------|--------|------|
| 1 | 试跑缓存异常 | 高 | ✅ 已关闭 |
| 2 | `save()` 隐式依赖排序 | 中 | ✅ 已关闭 |
| 3 | 分页缺测试 | 中 | ✅ 已关闭（部分采纳） |
| 4 | 文档未同步 | 中 | ✅ 已关闭（2026-06-07 全面整理） |
| 5 | 东财遗留 | 低 | ✅ 已关闭（`@Deprecated` 保留） |
| 6 | `hasQfqAdjustment` 包可见性 | 低 | ⏭ 忽略 |

`mvn -q test` 全部通过。

---

## 1. 试跑缓存异常 — ✅ 已关闭

### 原现象

`sh600519.json` 日期乱序、`last_updated` 错误、历史段负 OHLC。

### 根因与处置

| 子问题 | 根因 | 处置 |
|--------|------|------|
| 乱序 / `last_updated` | `fetch()` 未排序；`save()` 取末条 | `fetch()` 排序；`save()` 写入前排序 + `max(date)` |
| 负 OHLC | 腾讯早期 qfq 脏数据 | `parseBar` 丢弃 OHLC ≤ 0 |
| 试跑文件 | — | 已删并重拉 |

### 重拉验证（茅台）

| 检查项 | 结果 |
|--------|------|
| 条数 | ~2424 |
| 日期范围 | 2016-06-06 … 2026-06-05 |
| `adjustment` | qfq |
| 负 OHLC | 无 |

### 已知限制

过滤后有效 bar 可能晚于上市日（腾讯早期 qfq 质量）。近几年趋势分析通常够用。

---

## 2. `save()` 隐式依赖排序 — ✅ 已关闭

`KLineCache.save()` 防御性排序；测试 `KLineCacheTest.testSaveSortsBarsAndUsesLatestDate`。

---

## 3. 分页逻辑 — ✅ 已关闭（部分采纳）

分页游标改为 `earliestBarDate()`（chunk 内 `min(date)`）。未做 mock HTTP 全链路测试。

---

## 4. 文档 — ✅ 已关闭

README、AGENT-HANDOFF 已对齐腾讯 qfq、Release 分发、新浪股票池。

---

## 5. 东财遗留 — ✅ 已关闭

`EastmoneyQfqKLineFetcher` 标 `@Deprecated`，生产用 `TencentQfqKLineFetcher`。

---

## 代码改动摘要

| 组件 | 变更 |
|------|------|
| `KLineCache.save()` | 排序 + `last_updated` 取最新日期 |
| `TencentQfqKLineFetcher` | `earliestBarDate()`、过滤无效 OHLC |
| 测试 | save 排序、分页合并、无效 OHLC |
| `EastmoneyQfqKLineFetcher` | `@Deprecated` |

---

## 迁移影响速查（仍有效）

| 维度 | 评估 |
|------|------|
| 旧缓存无 `adjustment` | non-fresh，整包替换 |
| 已有 `adjustment=qfq` | 增量 merge |
| `isFresh` | 要求「今日 + qfq」 |
| 指数 | `FetchIndicesCli` / Step 2、4，腾讯源 |
| 单股 HTTP | 分页约 8 次/股（5000 ÷ 640） |
| 北交所 | 不支持（仅 sh/sz） |

---

## 相关源码

- `java/src/main/java/monitor/trendfollowing/TencentQfqKLineFetcher.java`
- `java/src/main/java/cache/KLineCache.java`
- `java/src/main/java/fetcher/BulkKLineFetcher.java`
- `java/src/main/java/FetchIndicesCli.java`

---

## 审阅结论

迁移方向正确，初版审阅项均已处置或明确忽略。全量跑批已于 2026-06-07 验收（3274 OK / 0 failed）。
