# 沪深两市每日量额接口

## 文件与口径

`cache/market/sh_sz_turnover.json` 是可跨项目读取的 UTF-8 JSON。无需安装本项目，不依赖复权状态、股票名单或 0AMV。日常 `python scripts/update_local_data.py` 自动维护；`--market-only` 可独立更新，无网络请求。

数据取自本机 `vipdoc/sh/lday/sh000001.day`（上证综指）和 `vipdoc/sz/lday/sz399106.day`（深证综指）。两个源按相同交易日对齐，相加各自的 `volume` 和 `amount`。不使用沪深 300、创业板等局部指数相加，以免漏计或重复计数；不以当前个股池回算历史，以免受缺失和退市文件影响。

这是 **通达信综合指数量额口径**，包含 B 股口径，不含北交所。它适合提供连续的市场量额观察序列，但不是纯 A 股汇总，也不是交易所全部证券成交统计，不能与其它口径无缝拼接。指数样本规则会影响覆盖范围。指数定义参考 [上证指数编制方案](https://www.sse.com.cn/market/sseindex/indexlist/indexdetails/indexmethods/c1/000001_000001hbook_CN.pdf) 与 [深证综指说明](https://www.cnindex.com.cn/html2pdf/preview/jj_399106.pdf)；数据数值始终以通达信本机文件为来源。

源成交量为手，乘 100 后输出股数；成交额按源人民币元值保存。`.day` 的成交额使用 float32，较大金额存在源精度限制，显示两位小数并不意味着精确到分。指数价格不进入此接口，量额不复权。

## 字段约定

| 字段 | 含义 |
|---|---|
| `symbol` | 固定为 `sh_sz_turnover` |
| `schema_version` | 当前为 `1` |
| `source` / `source_symbols` | `tongdaxin_local` / `{"sh":"sh000001","sz":"sz399106"}` |
| `scope` | `tdx_sh000001_plus_sz399106`，用于防止混用统计口径 |
| `volume_unit` / `amount_unit` | `shares` / `CNY` |
| `adjustment` / `amount_precision` | `none` / `source_float32` |
| `first_date` / `last_updated` | 实际数据起止日期，非程序运行日期 |
| `bar_count` | `bars` 的记录数 |
| `bars[].date` | `YYYY-MM-DD`，严格升序且唯一 |
| `bars[].sh_volume` / `sz_volume` / `volume` | 沪、深、合计成交股数 |
| `bars[].sh_amount` / `sz_amount` / `amount` | 沪、深、合计人民币成交额 |

不存在的日期不插入记录。首次创建仅输出两份源共有的完整日期区间；以后保留源窗口之前的已验收历史，重叠区间采用本地源的修订值，改写前备份。如果任一源缺失、为空、零量额、日期倒序、重叠区间缺日、两边末日不同、末日回退或无历史衔接，保留原文件并返回错误。两份源都停止在同一个旧日期时不会自动认定为最新交易日，下游必须自行检查目标日期。

## Python 读取示例

```python
import json
from pathlib import Path

# 路径由使用方配置；这是本机示例。
data_root = Path(r'D:\cursorworkspace\a-trend-data')
data = json.loads((data_root / 'cache/market/sh_sz_turnover.json').read_text(encoding='utf-8'))
assert data['schema_version'] == 1
assert data['scope'] == 'tdx_sh000001_plus_sz399106'
assert data['volume_unit'] == 'shares' and data['amount_unit'] == 'CNY'
assert data['bar_count'] == len(data['bars'])
assert data['last_updated'] == data['bars'][-1]['date']

latest = data['bars'][-1]
print(latest['date'], latest['volume'] / 1e8, latest['amount'] / 1e8)  # 亿股、亿元
# 若有目标交易日，先检查 latest['date']，不足时停止当天计算。
# 下游按自身需要计算均量、均额和阶段指标，指标不存入本数据文件。
```

## 分发与诊断

本机文件可直接共享给其它项目。三种打包入口均在该文件存在时将它加入 `kline-latest.tar.zst`，缺少时输出警告并兼容旧 K 线包；不打包 `cache/market` 内其它来源不明的数据。旧 Release 附件需要用户明确发布后才会更新。消费方仍需检查文件是否存在与实际末日。

`logs/local-update-latest.json` 的 `counts/dates` 继续只统计 K 线；`market` 单独报告量额文件的 `action/written_files/first_date/last_date/bar_count/elapsed_seconds`。`--dry-run` 的 `written_files` 永远为零。错误也进入顶层 `errors`，CLI 返回非零。备份在当次 `logs/cron/local-*/backup/market/`，增量状态属于本机，不可随数据分发。
