# 可选联网更新与发布

这份文档保留原有 Java 管道与 Release 操作。它们不是本机日常增量更新入口；日常使用 `python scripts/update_local_data.py`。

## Java 联网入口

需要可用 JDK、Maven 和本地 Maven 中的 `com.atrend:a-trend:1.0.0` 编译依赖。该路径使用新浪股票列表及腾讯行情，范围与本机全部 A 股文件可能不同。若改写了本地缓存，下次本地更新会检测到缓存状态变化并重新处理对应文件。

```powershell
cd java
mvn compile -q -DskipTests
mvn -q exec:java -Dexec.mainClass=DataUpdateCli "-Dexec.args=--repo-root D:\cursorworkspace\a-trend-data --mode incremental --no-push"
```

`--no-push` 只更新本地；去掉它会执行原管道的发布步骤。`scripts/daily_fetch.sh` 为原云端任务入口，不读取当前电脑的通达信目录。

## K 线数据包

Release `latest` 中 `kline-latest.tar.zst` 包含 `cache/kline/`、`config/stock-universe.json`、`logs/fetch-log.json`。打包时若本地存在 `cache/market/sh_sz_turnover.json`，也纳入这份两市量额数据；不存在则打印警告。旧附件不会自动新增该文件，读取方需检查文件存在性和实际末日。它不包含本机增量状态、历史基准、备份或 0AMV。

```powershell
powershell -File scripts/download-latest-release.ps1 -RepoRoot D:\cursorworkspace\a-trend-data
powershell -File scripts/publish-latest-release.ps1 -RepoRoot D:\cursorworkspace\a-trend-data
```

前置工具为 `gh`、`zstd`、`tar`；发布会替换远端附件，只在用户要求发布时执行。Python 备用打包与发布入口分别为 `scripts/package-kline-bundle.py`、`scripts/publish-latest-release-api.py`。

本地更新的详细报告为 `logs/local-update-latest.json`，原 Java 拉取日志为 `logs/fetch-log.json`，两者的运行日期与范围应分别核对。
