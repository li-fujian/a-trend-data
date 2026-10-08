import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from tdx_local.market import build_payload, update_market, SYMBOLS
from tdx_local.update import run


def packed(day, volume=100, amount=10000):
    return struct.pack('<IIIIIfII', int(day.replace('-', '')), 1000, 1100, 900, 1000,
                       amount, volume, 0)


class MarketTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)/'repo'
        self.tdx = Path(self.tmp.name)/'tdx'
        self.paths = {m: self.tdx/'vipdoc'/m/'lday'/f'{s}.day' for m, s in SYMBOLS.items()}
        for m, p in self.paths.items():
            p.parent.mkdir(parents=True)
            p.write_bytes(packed('2026-09-24', 100 if m == 'sh' else 200))
        self.dest = self.repo/'cache/market/sh_sz_turnover.json'
        self.backup = self.repo/'backup'

    def update(self, prior=None, write=True):
        return update_market(self.repo, self.tdx, prior, self.backup, write)

    def append(self, day):
        for p in self.paths.values():
            p.write_bytes(p.read_bytes()+packed(day))

    def test_units_sum_and_no_network(self):
        with patch('socket.socket', side_effect=AssertionError('network forbidden')):
            _, result = self.update()
        data = json.loads(self.dest.read_text(encoding='utf-8'))
        row = data['bars'][-1]
        self.assertEqual(row['volume'], 30000)
        self.assertEqual(row['sh_volume'], 10000)
        self.assertEqual(row['sz_volume'], 20000)
        self.assertEqual(row['amount'], 20000)
        self.assertEqual(data['last_updated'], '2026-09-24')
        self.assertEqual(result['written_files'], 1)

    def test_skip_without_parsing_or_writing(self):
        state, _ = self.update()
        before = self.dest.stat().st_mtime_ns
        with patch('tdx_local.market.read_bars', side_effect=AssertionError('unnecessary parse')):
            _, result = self.update(state)
        self.assertEqual(result['action'], 'unchanged')
        self.assertEqual(result['written_files'], 0)
        self.assertEqual(self.dest.stat().st_mtime_ns, before)

    def test_append_revision_and_backup(self):
        state, _ = self.update()
        original = self.dest.read_bytes()
        self.append('2026-09-28')
        state, _ = self.update(state)
        self.assertEqual((self.backup/'market'/self.dest.name).read_bytes(), original)
        self.paths['sh'].write_bytes(packed('2026-09-24', 999)+packed('2026-09-28'))
        self.update(state)
        data = json.loads(self.dest.read_text(encoding='utf-8'))
        self.assertEqual(data['bars'][0]['sh_volume'], 99900)
        self.assertEqual(data['bar_count'], 2)

    def test_dry_run_leaves_cache_and_state_untouched(self):
        report = run(self.repo, self.tdx, write=False, market_only=True, progress=False)
        self.assertEqual(report['market']['action'], 'create')
        self.assertEqual(report['market']['written_files'], 0)
        self.assertFalse(self.dest.exists())
        self.assertFalse((self.repo/'cache/.local-update/state.json').exists())
        self.update()
        original = self.dest.read_bytes()
        self.append('2026-09-28')
        self.update(write=False)
        self.assertEqual(self.dest.read_bytes(), original)

    def test_missing_empty_and_malformed_source_fail_closed(self):
        for blob in (None, b'', b'bad', packed('2026-09-24', amount=0),
                     packed('2026-09-24', amount=float('nan'))):
            with self.subTest(blob=blob):
                self.paths['sh'].write_bytes(packed('2026-09-24'))
                state, _ = self.update()
                before = self.dest.read_bytes()
                if blob is None:
                    self.paths['sh'].unlink()
                else:
                    self.paths['sh'].write_bytes(blob)
                with self.assertRaises(ValueError):
                    self.update(state)
                self.assertEqual(self.dest.read_bytes(), before)

    def test_one_market_lag_and_rollback_preserve_cache(self):
        state, _ = self.update()
        original = self.dest.read_bytes()
        self.paths['sh'].write_bytes(packed('2026-09-24')+packed('2026-09-28'))
        with self.assertRaisesRegex(ValueError, '末日不一致'):
            self.update(state)
        self.assertEqual(self.dest.read_bytes(), original)
        for p in self.paths.values():
            p.write_bytes(packed('2026-09-23'))
        with self.assertRaisesRegex(ValueError, '末日回退'):
            self.update(state)
        self.assertEqual(self.dest.read_bytes(), original)

    def test_missing_internal_dates_not_silently_intersected(self):
        sh = packed('2026-09-23')+packed('2026-09-24')+packed('2026-09-28')
        sz = packed('2026-09-23')+packed('2026-09-28')
        with self.assertRaisesRegex(ValueError, '缺少交易日'):
            build_payload({'sh': sh, 'sz': sz})
        old = build_payload({'sh': sh, 'sz': sh})
        with self.assertRaisesRegex(ValueError, '缺失已有交易日'):
            build_payload({'sh': sz, 'sz': sz}, old)

    def test_rolling_source_retains_earlier_history(self):
        self.append('2026-09-28')
        state, _ = self.update()
        for p in self.paths.values():
            p.write_bytes(packed('2026-09-28')+packed('2026-09-29'))
        self.update(state)
        data = json.loads(self.dest.read_text(encoding='utf-8'))
        self.assertEqual([b['date'] for b in data['bars']],
                         ['2026-09-24', '2026-09-28', '2026-09-29'])

    def test_source_change_during_read_fails(self):
        from tdx_local.market import read_bars
        def unstable(blob, symbol):
            result = read_bars(blob, symbol)
            self.paths['sh'].write_bytes(packed('2026-09-28'))
            return result
        with patch('tdx_local.market.read_bars', side_effect=unstable):
            with self.assertRaisesRegex(ValueError, '正在被改写'):
                self.update()
        self.assertFalse(self.dest.exists())

    def test_daily_integration_and_market_only_without_gbbq(self):
        first = run(self.repo, self.tdx, market_only=True, progress=False)
        self.assertFalse(first['errors'])
        self.assertEqual(first['symbols'], 0)
        self.assertEqual(first['market']['written_files'], 1)
        gbbq = self.tdx/'T0002/hq_cache/gbbq'
        gbbq.parent.mkdir(parents=True)
        gbbq.write_bytes(struct.pack('<I', 0))
        second = run(self.repo, self.tdx, progress=False)
        self.assertFalse(second['errors'])
        self.assertEqual(second['market']['action'], 'unchanged')
        self.paths['sz'].unlink()
        failure = run(self.repo, self.tdx, market_only=True, progress=False)
        self.assertEqual(failure['market']['action'], 'error')
        self.assertEqual(failure['errors'][0]['symbol'], 'sh_sz_turnover')


if __name__ == '__main__':
    unittest.main()
