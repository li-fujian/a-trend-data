#!/usr/bin/env python3
"""Convert this computer's TongdaXin files into the project's daily caches."""
import argparse
import json
import sys
from pathlib import Path
from tdx_local.update import run

def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description='通达信本机日线增量更新（不联网）')
    parser.add_argument('--repo-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--tdx-dir', type=Path, default=Path(r'E:\new_tdx64'))
    parser.add_argument('--dry-run', action='store_true', help='只预览，不写行情缓存或增量状态')
    parser.add_argument('--symbol', action='append', help='只处理指定代码，可重复')
    parser.add_argument('--market-only', action='store_true', help='只更新沪深两市成交量与成交额，不读取个股或除权文件')
    args = parser.parse_args()
    if args.market_only and args.symbol:
        parser.error('--market-only 与 --symbol 不能同时使用')
    report = run(args.repo_root, args.tdx_dir, write=not args.dry_run, selected=args.symbol,
                 market_only=args.market_only)
    print(json.dumps({k:v for k,v in report.items() if k!='results'}, ensure_ascii=False, indent=2))
    return 1 if report['errors'] else 0

if __name__=='__main__':
    raise SystemExit(main())
