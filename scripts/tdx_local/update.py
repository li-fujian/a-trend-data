"""Local-only incremental cache updates. All I/O is filesystem I/O."""
from collections import Counter
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import time

from .adjust import METHOD, effective_actions, forward_adjust
from .reader import DAY, decode_actions, iso_date, read_bars

INDICES = {'sh000001','sh000300','sh000688','sh000905','sz399001','sz399006'}
VERSION = 1

def fingerprint(data):
    return hashlib.sha256(data).hexdigest()

def signature(path):
    if not path.is_file():
        return None
    s = path.stat()
    return [s.st_size, s.st_mtime_ns]

def action_hash(rows):
    return fingerprint(json.dumps(rows, separators=(',', ':')).encode())

def read_json(path, default=None):
    return json.loads(path.read_text(encoding='utf-8')) if path.is_file() else default

def atomic_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.'+path.name, suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as handle:
            json.dump(payload, handle, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
            handle.write('\n')
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)

@contextmanager
def update_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as handle:
        handle.seek(0, 2)
        if not handle.tell():
            handle.write(b'0'); handle.flush()
        handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise RuntimeError('已有本地更新任务正在运行') from exc
        try:
            yield
        finally:
            if os.name == 'nt':
                handle.seek(0); msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

def is_stock(symbol):
    market, code = symbol[:2], symbol[2:]
    if len(code) != 6 or not code.isdigit():
        return False
    if market == 'sh':
        return code.startswith(('600','601','603','605','688','689'))
    if market == 'sz':
        return code.startswith(('000','001','002','003','300','301'))
    return market == 'bj' and code.startswith(('43','83','87','88','92'))

def discover(tdx, cache, selected=None):
    sources = {}
    for market in ('sh','sz','bj'):
        for p in (tdx/'vipdoc'/market/'lday').glob('*.day'):
            s = p.stem.lower()
            if is_stock(s) or s in INDICES or (cache/f'{s}.json').is_file():
                sources[s] = p
    if selected:
        invalid = [s for s in selected if len(s)!=8 or s[:2] not in ('sh','sz','bj') or not s[2:].isdigit()]
        if invalid:
            raise ValueError(f'无效代码: {invalid}')
        return {s: tdx/'vipdoc'/s[:2]/'lday'/f'{s}.day' for s in sorted(set(selected))}
    # Report missing sources for existing files instead of silently dropping them.
    for p in cache.glob('*.json'):
        s = p.stem
        sources.setdefault(s, tdx/'vipdoc'/s[:2]/'lday'/f'{s}.day')
    return dict(sorted(sources.items()))

def read_actions(tdx, state_dir, write):
    path = tdx/'T0002/hq_cache/gbbq'
    blob = path.read_bytes()
    digest = fingerprint(blob)
    decoded = read_json(state_dir/'actions.json')
    if decoded and decoded.get('version')==VERSION and decoded.get('sha256')==digest:
        return decoded['actions'], digest, False
    actions = decode_actions(blob)
    if write:
        atomic_json(state_dir/'actions.json', dict(version=VERSION, sha256=digest, actions=actions))
    return actions, digest, True

def make_payload(symbol, bars, baseline=None):
    if not bars:
        raise ValueError('没有有效日线')
    payload = dict(symbol=symbol, adjustment='qfq', schema_version=2, volume_unit='shares',
                   source='tongdaxin_local', adjustment_method=METHOD,
                   last_updated=bars[-1]['date'], bar_count=len(bars), bars=bars)
    if baseline:
        payload['history_origin'] = 'preserved_qfq_baseline'
        payload['baseline_date'] = baseline
    else:
        payload['history_origin'] = 'tongdaxin_local'
    return payload

def normalize_baseline(symbol, old, raw):
    if old.get('adjustment')!='qfq' or old.get('volume_unit')!='shares' or old.get('schema_version')!=2:
        raise ValueError('旧缓存口径未知，保留原文件')
    rows = old['bars']
    last = rows[-1]['date']
    anchor = next((b for b in raw if b['date']==last), None)
    if anchor is None:
        raise ValueError(f'本地日线缺少旧缓存衔接日 {last}')
    if any(abs(rows[-1][f]-anchor[f])>0.011 for f in ('open','high','low','close')):
        raise ValueError(f'旧缓存末日价格与本地衔接日 {last} 不同，保留原文件')
    # Legacy index files were sometimes labelled shares while still storing lots.
    if symbol in INDICES and rows[-1]['volume']:
        ratio = anchor['volume']/rows[-1]['volume']
        # Identify the unit order of magnitude, not provider-specific volume revisions.
        if 50 < ratio < 200:
            rows = [dict(b, volume=int(round(b['volume']*100))) for b in rows]
        elif not 0.5 < ratio < 2:
            raise ValueError('旧指数成交量单位无法识别，保留原文件')
        local_by_date = {b['date']: b for b in raw}
        rows = [dict(b, volume=local_by_date[b['date']]['volume']) if b['date'] in local_by_date else b for b in rows]
    return make_payload(symbol, rows, last)

def from_baseline(symbol, baseline, raw, actions):
    day = baseline['bars'][-1]['date']
    segment = [b for b in raw if b['date']>=day]
    if not segment or segment[0]['date']!=day:
        raise ValueError(f'本地日线缺少历史基准日 {day}')
    reference = baseline['bars'][-1]
    if any(abs(reference[f]-segment[0][f])>0.011 for f in ('open','high','low','close')):
        raise ValueError(f'本地历史基准日 {day} 已修订，保留旧缓存待定向处理')
    future = [a for a in actions if a[0]>day]
    adjusted = forward_adjust(segment, future)
    factor = adjusted[0]['close']/segment[0]['close']
    prefix = []
    for b in baseline['bars']:
        item = dict(b)
        for field in ('open','high','low','close'):
            item[field] = round(b[field]*factor, 8)
        prefix.append(item)
    return make_payload(symbol, prefix+adjusted[1:], day)

def process(symbol, source, dest, prior, all_actions, state_dir, backup_dir, write):
    source_stat, dest_stat = signature(source), signature(dest)
    if source_stat is None:
        raise ValueError('本机没有对应日线文件，保留缓存')
    if not source_stat[0]:
        return None, dict(symbol=symbol, action='empty_source', last_date=None)
    if source_stat[0] % DAY.size:
        raise ValueError('日线文件长度不是 32 的整数倍')
    if prior and prior.get('version')==VERSION:
        effective = effective_actions(all_actions, prior['last_date'], symbol)
        same_actions = action_hash(effective)==prior['actions_hash']
        if source_stat==prior['source_stat'] and dest_stat==prior['cache_stat'] and same_actions:
            return prior, dict(symbol=symbol, action='unchanged', last_date=prior['last_date'])
    blob = source.read_bytes()
    if signature(source)!=source_stat:
        raise ValueError('软件正在改写日线，请下载完成后重试')
    digest = fingerprint(blob)
    last_date = iso_date(DAY.unpack_from(blob, len(blob)-DAY.size)[0])
    if prior and (last_date < prior['last_date'] or len(blob) < prior['source_bytes']):
        raise ValueError('本地日线日期回退或历史缩短，保留已有缓存')
    effective = effective_actions(all_actions, last_date, symbol)
    actions_digest = action_hash(effective)
    if prior and digest==prior.get('source_sha256') and actions_digest==prior.get('actions_hash') and dest_stat==prior.get('cache_stat'):
        current = dict(prior, source_stat=source_stat)
        return current, dict(symbol=symbol, action='unchanged', last_date=last_date)
    baseline_path = state_dir/'baselines'/f'{symbol}.json'
    baseline_date = prior.get('baseline_date') if prior else None
    baseline_actions_hash = prior.get('baseline_actions_hash') if prior else None
    if baseline_date and action_hash([a for a in effective if a[0]<=baseline_date])!=baseline_actions_hash:
        raise ValueError('继承历史基准之前的除权记录已修订，需要定向处理；保留缓存')
    raw = None
    action = 'rebuild'
    # True append: verify the old binary prefix, read only new bar records and the current JSON.
    can_append = (prior and dest_stat==prior['cache_stat'] and len(blob)>prior['source_bytes']
                  and fingerprint(blob[:prior['source_bytes']])==prior['source_sha256']
                  and action_hash(effective_actions(all_actions, prior['last_date'], symbol))==prior['actions_hash']
                  and not any(a[0]>prior['last_date'] for a in effective))
    if can_append:
        old = read_json(dest)
        if old.get('source')!='tongdaxin_local' or old['bars'][-1]['date']!=prior['last_date']:
            raise ValueError('缓存与增量状态不一致')
        additions = read_bars(blob[prior['source_bytes']:], symbol, prior['last_date'])
        payload = make_payload(symbol, old['bars']+additions, baseline_date)
        action = 'append'
    else:
        raw = read_bars(blob, symbol)
        if baseline_date:
            baseline = read_json(baseline_path)
            if baseline is None:
                raise ValueError('缺少继承历史基准文件，保留缓存')
            payload = from_baseline(symbol, baseline, raw, effective)
        elif dest.is_file() and not prior:
            old = read_json(dest)
            if old['bars'][-1]['date']>last_date:
                raise ValueError(f'本地末日 {last_date} 早于缓存，保留较新缓存')
            if old.get('source')=='tongdaxin_local' and old.get('baseline_date'):
                baseline_date = old['baseline_date']
                baseline = read_json(baseline_path)
                if baseline is None:
                    raise ValueError('缺少继承历史基准文件，保留缓存')
            else:
                baseline = normalize_baseline(symbol, old, raw)
                baseline_date = baseline['baseline_date']
            baseline_actions_hash = action_hash([a for a in effective if a[0]<=baseline_date])
            payload = from_baseline(symbol, baseline, raw, effective)
            if write and not baseline_path.exists():
                atomic_json(baseline_path, baseline)
            action = 'adopt_and_update'
        else:
            payload = make_payload(symbol, forward_adjust(raw, effective))
            action = 'create' if not dest.exists() else 'rebuild'
    if payload['last_updated']!=last_date:
        raise ValueError('输出末日与源文件不一致')
    if signature(source)!=source_stat or signature(dest)!=dest_stat:
        raise ValueError('准备期间文件被外部修改，请重试')
    if write:
        if dest.is_file():
            backup_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, backup_dir/dest.name)
        atomic_json(dest, payload)
    current = dict(version=VERSION, source_stat=source_stat, source_sha256=digest,
                   source_bytes=len(blob), last_date=last_date, actions_hash=actions_digest,
                   cache_stat=signature(dest), baseline_date=baseline_date,
                   baseline_actions_hash=baseline_actions_hash, bar_count=payload['bar_count'])
    return current, dict(symbol=symbol, action=action, last_date=last_date,
                         bar_count=payload['bar_count'], source_bars=len(blob)//DAY.size)

def run(repo, tdx, *, write=True, selected=None, progress=True, market_only=False):
    from .market import update_market
    if market_only and selected:
        raise ValueError('--market-only 与 --symbol 不能同时使用')
    started = time.perf_counter()
    repo, tdx = Path(repo).resolve(), Path(tdx).resolve()
    cache = repo/'cache/kline'
    state_dir = repo/'cache/.local-update'
    run_id = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup_dir = repo/'logs/cron'/('local-'+run_id)/'backup'
    with update_lock(state_dir/'update.lock'):
        manifest = read_json(state_dir/'state.json', {})
        if manifest.get('version')!=VERSION or manifest.get('tdx_root')!=str(tdx):
            manifest = dict(version=VERSION, tdx_root=str(tdx), symbols={})
        actions, gbbq_hash, decoded = ({}, None, False) if market_only else read_actions(tdx, state_dir, write)
        sources = {} if market_only else discover(tdx, cache, selected)
        results, errors = [], []
        for number, (symbol, source) in enumerate(sources.items(), 1):
            try:
                prior = manifest['symbols'].get(symbol)
                state, row = process(symbol, source, cache/f'{symbol}.json', prior,
                                     actions.get(symbol, []), state_dir, backup_dir, write)
                if state is not None:
                    manifest['symbols'][symbol] = state
                results.append(row)
            except Exception as exc:
                errors.append(dict(symbol=symbol, error=str(exc)))
            if write and number%250==0:
                atomic_json(state_dir/'state.json', manifest)
            if progress and number%500==0:
                print(f'{number}/{len(sources)}  errors={len(errors)}  {time.perf_counter()-started:.1f}s', flush=True)
        market = dict(action='skipped_selected_symbols', written_files=0)
        if not selected:
            try:
                market_state, market = update_market(repo, tdx, manifest.get('market'), backup_dir, write)
                manifest['market'] = market_state
            except Exception as exc:
                market = dict(action='error', written_files=0, error=str(exc))
                errors.append(dict(symbol='sh_sz_turnover', error=str(exc)))
        dates = Counter(r['last_date'] for r in results if r['last_date'])
        target = max(dates, default=None)
        counts = dict(Counter(r['action'] for r in results))
        report = dict(run_id=run_id, mode='write' if write else 'dry-run', source='tongdaxin_local',
                      network_requests=0, tdx_root=str(tdx), target_date=target, symbols=len(sources),
                      counts=counts, dates=dict(sorted(dates.items(), reverse=True)), market=market,
                      errors=errors, gbbq_sha256=gbbq_hash, decoded_gbbq=decoded,
                      elapsed_seconds=round(time.perf_counter()-started, 3), results=results,
                      backup_dir=str(backup_dir) if backup_dir.exists() else None)
        if write:
            manifest['last_run'] = run_id
            atomic_json(state_dir/'state.json', manifest)
            atomic_json(backup_dir.parent/'report.json', report)
            atomic_json(repo/'logs/local-update-latest.json', report)
        return report
