while ($true) {
    Start-Sleep -Seconds 600
    Write-Output 'AGENT_LOOP_TICK_kline {"prompt":"运行 E:/code/a-trend-data/scripts/sample-kline-check.ps1 做前复权缓存抽样校验；读取 DataUpdateCli 终端 230496 末尾看 Step3 进度；汇报 qfq 覆盖率、抽样 PASS/FAIL、新发现问题。若 Step3 已完成则继续盯 Step4-6 并说明是否可停止循环。"}'
}
