import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from tdx_local.adjust import forward_adjust
from tdx_local.reader import read_bars, decode_actions
from tdx_local.update import atomic_json, normalize_baseline, process, run, update_lock

def packed(day, close=10, volume=1000):
    return struct.pack('<IIIIIfII', int(day.replace('-','')), round(close*100),
                       round((close+1)*100), round((close-1)*100), round(close*100),
                       10000., volume, 0)

def bars(*pairs):
    return read_bars(b''.join(packed(d,c) for d,c in pairs), 'sh600000')

class FormatTests(unittest.TestCase):
    def test_dates_price_and_units(self):
        blob=packed('2026-09-24',12.34,2345)
        self.assertEqual(read_bars(blob,'sh600000')[0]['volume'],2345)
        self.assertEqual(read_bars(blob,'sh000001')[0]['volume'],234500)
        self.assertEqual(read_bars(blob,'sh600000')[0]['close'],12.34)
        for broken in (blob+b'0',blob+blob,packed('2026-02-30')):
            with self.assertRaises(ValueError):read_bars(broken,'sh600000')
        with self.assertRaises(ValueError):decode_actions(b'bad')

    def test_cash_bonus_rights_and_split(self):
        source=bars(('2026-09-24',10),('2026-09-28',8))
        # 10 old shares: cash 2, rights 2 shares at 3, bonus 4 shares.
        result=forward_adjust(source,[['2026-09-28',1,2,3,4,2]])
        self.assertAlmostEqual(result[0]['close'],6.5)
        self.assertEqual(result[-1]['close'],8)
        self.assertEqual(result[0]['volume'],1000)
        split=forward_adjust(source,[['2026-09-28',11,0,0,2,0]])
        self.assertEqual(split[0]['close'],5)

    def test_nontrading_day_and_multiple_actions(self):
        source=bars(('2026-09-24',10),('2026-09-28',9))
        result=forward_adjust(source,[['2026-09-25',1,10,0,0,0],['2026-09-26',1,10,0,0,0]])
        self.assertAlmostEqual(result[0]['close'],8)
        self.assertEqual(forward_adjust(source,[['2026-10-01',1,10,0,0,0]]),source)

class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.source=self.root/'sh600000.day';self.dest=self.root/'cache/sh600000.json'
        self.state=self.root/'state';self.backup=self.root/'backup'
        self.source.write_bytes(packed('2026-09-24'))

    def tearDown(self):self.tmp.cleanup()

    def update(self,prior=None,actions=(),write=True):
        return process('sh600000',self.source,self.dest,prior,list(actions),self.state,self.backup,write)

    def test_create_append_unchanged_without_network_or_cache_read(self):
        with patch('socket.socket',side_effect=AssertionError('network is forbidden')):
            state,row=self.update();self.assertEqual(row['action'],'create')
            initial=json.loads(self.dest.read_text())
            self.source.write_bytes(self.source.read_bytes()+packed('2026-09-28',11))
            state,row=self.update(state);self.assertEqual(row['action'],'append')
            current=json.loads(self.dest.read_text());self.assertEqual(current['bars'][0],initial['bars'][0])
            before=self.dest.stat().st_mtime_ns
            with patch('tdx_local.update.read_json',side_effect=AssertionError('unnecessary JSON read')):
                state,row=self.update(state)
            self.assertEqual(row['action'],'unchanged');self.assertEqual(self.dest.stat().st_mtime_ns,before)

    def test_legacy_history_is_preserved_and_locally_rebased(self):
        old=bars(('2005-01-04',5),('2026-09-24',10))
        atomic_json(self.dest,dict(adjustment='qfq',schema_version=2,volume_unit='shares',bars=old))
        self.source.write_bytes(packed('2026-09-24')+packed('2026-09-28',9))
        actions=[['2026-09-28',1,10,0,0,0]]
        state,row=self.update(actions=actions)
        data=json.loads(self.dest.read_text())
        self.assertEqual(data['bars'][0]['date'],'2005-01-04')
        self.assertEqual(data['bars'][0]['close'],4.5)
        self.assertEqual(data['bars'][1]['close'],9)
        self.assertTrue((self.backup/self.dest.name).exists())
        # Corrected event is rebuilt from the immutable original baseline, not compounded twice.
        state,row=self.update(state,[['2026-09-28',1,20,0,0,0]])
        self.assertEqual(json.loads(self.dest.read_text())['bars'][0]['close'],4)
        self.assertEqual(row['action'],'rebuild')

    def test_raw_history_revision_rebuilds_local_file(self):
        state,_=self.update()
        self.source.write_bytes(packed('2026-09-24',11))
        state,row=self.update(state)
        self.assertEqual(row['action'],'rebuild')
        self.assertEqual(json.loads(self.dest.read_text())['bars'][0]['close'],11)

    def test_invalid_source_does_not_replace_cache(self):
        state,_=self.update();original=self.dest.read_bytes()
        self.source.write_bytes(b'incomplete')
        with self.assertRaises(ValueError):self.update(state)
        self.assertEqual(original,self.dest.read_bytes())

    def test_source_rollback_keeps_newer_cache(self):
        self.source.write_bytes(packed('2026-09-24')+packed('2026-09-28',11))
        state,_=self.update();original=self.dest.read_bytes()
        self.source.write_bytes(packed('2026-09-24'))
        with self.assertRaisesRegex(ValueError,'日期回退或历史缩短'):self.update(state)
        self.assertEqual(original,self.dest.read_bytes())

    def test_index_legacy_units_use_local_volume(self):
        old=bars(('2005-01-04',5),('2026-09-24',10))
        raw=read_bars(packed('2026-09-24',10,1037),'sz399006')
        result=normalize_baseline('sz399006',dict(adjustment='qfq',schema_version=2,volume_unit='shares',bars=old),raw)
        self.assertEqual(result['bars'][0]['volume'],100000)
        self.assertEqual(result['bars'][-1]['volume'],103700)

    def test_dry_run_and_future_action(self):
        _,row=self.update(write=False);self.assertFalse(self.dest.exists())
        state,_=self.update()
        state,row=self.update(state,[['2026-10-01',1,10,0,0,0]])
        self.assertEqual(row['action'],'unchanged')

    def test_lost_manifest_recovers_legacy_baseline(self):
        atomic_json(self.dest,dict(adjustment='qfq',schema_version=2,volume_unit='shares',bars=bars(('2005-01-04',5),('2026-09-24',10))))
        state,_=self.update()
        self.source.write_bytes(self.source.read_bytes()+packed('2026-09-28',11))
        self.update(None)
        self.assertEqual(json.loads(self.dest.read_text())['bars'][0]['date'],'2005-01-04')

    def test_old_gbbq_timestamp_does_not_block_offline_run(self):
        tdx=self.root/'tdx';daily=tdx/'vipdoc/sh/lday';daily.mkdir(parents=True)
        (daily/'sh600000.day').write_bytes(packed('2026-09-28'))
        gbbq=tdx/'T0002/hq_cache/gbbq';gbbq.parent.mkdir(parents=True);gbbq.write_bytes(struct.pack('<I',0))
        os.utime(gbbq,(1,1))
        with patch('socket.socket',side_effect=AssertionError('network is forbidden')):
            first=run(self.root/'repo',tdx,progress=False,selected=['sh600000'])
            second=run(self.root/'repo',tdx,progress=False,selected=['sh600000'])
        self.assertFalse(first['errors']);self.assertEqual(first['network_requests'],0)
        self.assertEqual(second['counts'],{'unchanged':1});self.assertFalse(second['decoded_gbbq'])

    def test_single_writer_lock(self):
        with update_lock(self.state/'lock'):
            with self.assertRaises(RuntimeError):
                with update_lock(self.state/'lock'):pass

if __name__=='__main__':unittest.main()
