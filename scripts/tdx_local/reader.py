"""Read local TongdaXin binary files; no network dependencies."""
from datetime import date
import math
import struct

from .gbbq_key import GBBQ_KEY

DAY = struct.Struct('<IIIIIfII')
WORDS = struct.unpack('<%dI' % (len(GBBQ_KEY) // 4), GBBQ_KEY)

def iso_date(value):
    return date(value // 10000, value // 100 % 100, value % 100).isoformat()

def read_bars(blob, symbol, previous=''):
    if len(blob) % DAY.size:
        raise ValueError('日线文件不是完整的 32 字节记录')
    result = []
    index = symbol.startswith('sh000') or symbol.startswith('sz399')
    for d, op, hi, lo, cl, amount, volume, _ in DAY.iter_unpack(blob):
        day = iso_date(d)
        if day <= previous:
            raise ValueError(f'日期重复或倒序: {day}')
        previous = day
        if not (0 < lo <= min(op, cl) <= max(op, cl) <= hi):
            raise ValueError(f'{day} OHLC 格式异常')
        if not math.isfinite(amount) or amount < 0:
            raise ValueError(f'{day} 成交额格式异常')
        result.append(dict(date=day, open=op/100, high=hi/100, low=lo/100,
                           close=cl/100, volume=volume*(100 if index else 1),
                           amount=round(amount, 2)))
    return result

def _decrypt(blob, offset):
    first, second = struct.unpack_from('<II', blob, offset)
    current, previous = WORDS[17] ^ first, second
    for j in range(16, 0, -1):
        mixed = (WORDS[274 + (current >> 16 & 255)] + WORDS[18 + (current >> 24)]) & 0xffffffff
        mixed ^= WORDS[530 + (current >> 8 & 255)]
        mixed = (mixed + WORDS[786 + (current & 255)]) & 0xffffffff
        current, previous = previous ^ mixed ^ WORDS[j], current
    return struct.pack('<II', previous ^ WORDS[0], current)

def decode_actions(blob):
    if len(blob) < 4:
        raise ValueError('除权文件缺少记录数')
    count = struct.unpack_from('<I', blob)[0]
    if len(blob) != 4 + count * 29:
        raise ValueError('除权文件长度与记录数不符')
    result = {}
    for pos in range(4, len(blob), 29):
        clear = _decrypt(blob, pos) + _decrypt(blob, pos+8)
        category = clear[12]
        # Category 12 concerns non-tradable shares, not a traded-price split.
        if category not in (1, 11):
            continue
        clear += _decrypt(blob, pos+16) + blob[pos+24:pos+29]
        market, raw_code, raw_date, category, c1, c2, c3, c4 = struct.unpack('<B7sIBffff', clear)
        code = raw_code.rstrip(b'\0').decode('ascii')
        prefix = {0:'sz', 1:'sh', 2:'bj'}.get(market)
        if prefix is None or len(code) != 6 or not code.isdigit():
            continue
        day = iso_date(raw_date)
        # c1/c2/c4 contain unrelated bit patterns for split records.
        fields = [c1, c2, c3, c4] if category == 1 else [0., 0., c3, 0.]
        if not all(math.isfinite(v) for v in fields):
            raise ValueError(f'{prefix}{code} {day} 除权字段异常')
        row = [day, category, *fields]
        result.setdefault(prefix+code, []).append(row)
    for symbol, rows in result.items():
        result[symbol] = [list(r) for r in sorted(set(tuple(r) for r in rows))]
    return result
