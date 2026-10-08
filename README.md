# 本机行情数据更新

本项目负责将通达信本地行情转换为统一 JSON，增量维护数据缓存。**以下默认路径和使用流程仅适用于当前这台 Windows 电脑。**

## 日常更新

1. 在通达信中完成盘后日线下载。
2. 在本项目目录执行：

```powershell
python scripts\update_local_data.py
```

默认读取 `E:\new_tdx64`，写入 `D:\cursorworkspace\a-trend-data\cache\kline`。只需要 Python 3.10+ 标准库；日常更新不使用 Java，不联网。

- 没有新数据：直接跳过，不重写缓存。
- 普通新增日线：按末日追加。
- 新增除权或本地数据改写：只重新计算受影响的标的。
- 首次接入：保留旧缓存的更早历史，以旧缓存末日为基准接入本地日线；原文件自动备份。
- 默认范围：本机已下载的沪深北 A 股、六个主要指数，以及已有缓存。范围不受股票列表的市值过滤限制。
- 同步维护沪深两市每日成交量、成交额汇总；两份本地指数源无变化时直接跳过。

只查看预估结果或处理单个文件：

```powershell
python scripts\update_local_data.py --dry-run
python scripts\update_local_data.py --symbol sh600000
```

不需要每次先全量预检再更新；正常直接运行第一条更新命令即可。

## 数据在哪里、怎样读取

| 路径 | 内容 |
|---|---|
| `cache/kline/{市场}{代码}.json` | 前复权日线，`bars` 按日期升序 |
| `cache/compass/0AMV.json` | 指南针活跃市值，独立更新 |
| `cache/market/sh_sz_turnover.json` | 沪深每日成交量、成交额，包含分市值与合计值 |
| `logs/local-update-latest.json` | 最近一次本地更新结果、耗时、日期覆盖和异常 |
| `logs/cron/local-*/backup/` | 每次改写前的旧文件备份 |
| `cache/.local-update/` | 增量状态、解码缓存和继承历史基准，属于本机运行数据 |

日线字段为 `date/open/high/low/close/volume`，本地新增记录另有 `amount`。`adjustment=qfq`、`schema_version=2`、`volume_unit=shares`：价格为前复权，成交量为实际股数，成交额为元，成交量不做复权。指数源文件的成交量按手换算为股。

`last_updated` 等于最后一根日线的日期，不是程序运行日期。个别文件末日较早且本地源同样没有新记录，程序会保留实际日期，不制造补齐记录。

旧缓存早于本地可用历史的部分会保留，文件通过 `history_origin=preserved_qfq_baseline` 和 `baseline_date` 标识；基准日之后按本地除权记录计算等比前复权。新建文件全部由本地日线生成。除权后历史前复权价格会改变，读取时应使用整份文件。

## 沪深两市成交量、成交额

其他项目直接读取 `cache/market/sh_sz_turnover.json`。`bars` 按日期升序，`volume` 为两市成交股数，`amount` 为两市成交金额（人民币元）；`sh_volume/sz_volume` 和 `sh_amount/sz_amount` 保留沪深分项，合计严格等于分项之和。展示为亿股、亿元时除以 `1e8`。

采用通达信本地 **上证综指 `sh000001` + 深证综指 `sz399106`** 的日线量额口径，含 B 股口径、不含北交所，不能标为纯 A 股统计或交易所全部证券（含基金、债券）成交总额。指数样本与全体上市股票也可能不同；这里保存通达信原始量额，不按当前股票池重新求和、不推断市场阶段。详细口径、字段和读取示例见 [两市量额接口](docs/market-turnover.md)。

正常日常命令自动更新。只需更新这份数据时执行：

```powershell
python scripts/update_local_data.py --market-only
```

`--market-only` 不读取个股和除权文件；可加 `--dry-run` 预览。`--symbol` 定向更新个股时不会更新两市汇总。末日以文件的 `last_updated` 为准；如果沪深末日不一致、重叠区间缺日或文件损坏，则保留旧汇总并报告异常，不填零拼接。最近运行报告的 `market` 字段单独给出汇总日期、写入数及耗时。

## 本机输入文件

- 通达信日线：`E:\new_tdx64\vipdoc\sh|sz|bj\lday\*.day`
- 通达信除权记录：`E:\new_tdx64\T0002\hq_cache\gbbq`
- 指南针源：`D:\Program Files\Compass\WavMain\ANALYSE\Data\ChinaStk\Z_SK\day.vdat`

程序识别实际内容和日期，不要求除权文件每天改变修改时间。格式损坏、衔接日期缺失等异常会明确列出，保留相关旧缓存；其它正常文件继续更新。换电脑时必须重新配置并核对路径，不能照搬本机状态目录。

## 0AMV 单独更新

在指南针里完成行情更新并退出软件，然后执行：

```powershell
python scripts\extract_compass_amv.py --repo-root D:\cursorworkspace\a-trend-data
```

以输出末日为准。`close` 为活跃市值，`volume` 为股，`amount` 为元。通达信下载不会更新这个文件，K 线 Release 包也不包含它。

## 其他说明

- [维护说明](AGENT-HANDOFF.md)：实现、异常处理和验证方式。
- [可选的联网更新与发布](docs/online-pipeline.md)：旧 Java 入口与 Release 操作，不属于本机日常增量流程。
- 本地 K 线、两市量额缓存、状态和备份不提交 Git；0AMV JSON 单独由 Git 跟踪。两市量额随下一次手动打包纳入 Release，远端现有附件不会自动改变。
