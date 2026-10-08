"""Shanghai + Shenzhen turnover from local broad-index daily files."""
from pathlib import Path
import shutil
import time

from .reader import read_bars
from .update import atomic_json, fingerprint, read_json, signature

SERIES = 'sh_sz_turnover'
SYMBOLS = {'sh': 'sh000001', 'sz': 'sz399106'}
VERSION = 1


def build_payload(blobs, old=None):
    sources = {market: read_bars(blobs[market], symbol)
               for market, symbol in SYMBOLS.items()}
    if any(not rows for rows in sources.values()):
        raise ValueError('两市量额源文件为空，保留旧缓存')
    first = max(rows[0]['date'] for rows in sources.values())
    last_dates = {market: rows[-1]['date'] for market, rows in sources.items()}
    if len(set(last_dates.values())) != 1:
        raise ValueError(f'两市量额源末日不一致: {last_dates}，保留旧缓存')
    aligned = {market: {b['date']: b for b in rows if b['date'] >= first}
               for market, rows in sources.items()}
    if not aligned['sh'] or aligned['sh'].keys() != aligned['sz'].keys():
        raise ValueError('两市量额源的重叠区间缺少交易日，保留旧缓存')
    bars = []
    for day, sh in aligned['sh'].items():
        sz = aligned['sz'][day]
        # A zero for either whole exchange is not a usable market session.
        if any(b[f] <= 0 for b in (sh, sz) for f in ('volume', 'amount')):
            raise ValueError(f'{day} 单市成交量或成交额为零，保留旧缓存')
        bars.append(dict(date=day, sh_volume=sh['volume'], sz_volume=sz['volume'],
                         volume=sh['volume'] + sz['volume'], sh_amount=sh['amount'],
                         sz_amount=sz['amount'], amount=round(sh['amount'] + sz['amount'], 2)))
    if old:
        if (old.get('schema_version') != VERSION or old.get('symbol') != SERIES
                or old.get('source_symbols') != SYMBOLS or old.get('volume_unit') != 'shares'
                or old.get('amount_unit') != 'CNY' or not old.get('bars')):
            raise ValueError('已有两市量额缓存口径未知，保留原文件')
        old_bars = old['bars']
        old_dates = [b['date'] for b in old_bars]
        if old_dates != sorted(set(old_dates)) or old['last_updated'] != old_dates[-1]:
            raise ValueError('已有两市量额缓存日期异常，保留原文件')
        if bars[-1]['date'] < old_dates[-1]:
            raise ValueError('两市量额源末日回退，保留较新缓存')
        if bars[0]['date'] > old_dates[-1]:
            raise ValueError('两市量额源与已有历史无重叠，无法验证衔接')
        if any(d >= first and d not in aligned['sh'] for d in old_dates):
            raise ValueError('两市量额源缺失已有交易日，保留旧缓存')
        # Local downloads may roll forward; retain earlier, already accepted history.
        bars = [b for b in old_bars if b['date'] < first] + bars
    return dict(symbol=SERIES, name='沪深两市成交量与成交额（通达信综合指数口径）',
                schema_version=VERSION, source='tongdaxin_local', source_symbols=SYMBOLS,
                scope='tdx_sh000001_plus_sz399106',
                scope_note='两份综合指数量额按同日相加；含B股口径，不含北交所；非纯A股或交易所全部证券官方统计。',
                volume_unit='shares', amount_unit='CNY', adjustment='none',
                amount_precision='source_float32',
                first_date=bars[0]['date'], last_updated=bars[-1]['date'],
                bar_count=len(bars), bars=bars)


def update_market(repo, tdx, prior, backup_dir, write):
    started = time.perf_counter()
    dest = Path(repo)/'cache/market'/f'{SERIES}.json'
    paths = {m: Path(tdx)/'vipdoc'/m/'lday'/f'{s}.day' for m, s in SYMBOLS.items()}
    stats = {m: signature(p) for m, p in paths.items()}
    dest_stat = signature(dest)
    if any(s is None for s in stats.values()):
        missing = [str(paths[m]) for m, s in stats.items() if s is None]
        raise ValueError(f'缺少两市量额源文件: {missing}，保留旧缓存')
    if (prior and prior.get('version') == VERSION and prior.get('source_stats') == stats
            and dest_stat is not None and prior.get('cache_stat') == dest_stat):
        return prior, dict(prior['coverage'], action='unchanged', written_files=0,
                           elapsed_seconds=round(time.perf_counter()-started, 3))
    blobs = {m: p.read_bytes() for m, p in paths.items()}
    old = read_json(dest)
    payload = build_payload(blobs, old)
    if stats != {m: signature(p) for m, p in paths.items()} or signature(dest) != dest_stat:
        raise ValueError('两市量额源或缓存正在被改写，请下载完成后重试')
    changed = payload != old
    action = ('create' if old is None else 'update') if changed else 'unchanged'
    if changed and write:
        if dest.is_file():
            backup = Path(backup_dir)/'market'/dest.name
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, backup)
        atomic_json(dest, payload)
    coverage = dict(path='cache/market/'+dest.name, first_date=payload['first_date'],
                    last_date=payload['last_updated'], bar_count=payload['bar_count'],
                    source_symbols=SYMBOLS)
    current = dict(version=VERSION, source_stats=stats,
                   source_sha256={m: fingerprint(b) for m, b in blobs.items()},
                   cache_stat=signature(dest), coverage=coverage)
    return current, dict(coverage, action=action, written_files=int(changed and write),
                         elapsed_seconds=round(time.perf_counter()-started, 3))
