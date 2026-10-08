"""Local proportional forward adjustment; actual share volume is unchanged."""
from bisect import bisect_left
import math

METHOD = 'tdx-local-proportional-v1'

def effective_actions(actions, last_date, symbol):
    if symbol.startswith('sh000') or symbol.startswith('sz399'):
        return []
    return [a for a in actions if a[0] <= last_date]

def forward_adjust(bars, actions):
    if not bars:
        return []
    dates = [b['date'] for b in bars]
    boundaries = {}
    previous_index = None
    reference = None
    for day, category, cash, rights_price, bonus, rights in actions:
        idx = bisect_left(dates, day)
        if idx == 0 or idx >= len(bars):
            continue
        if idx != previous_index:
            reference = bars[idx-1]['close']
        if category == 1:
            denominator = 10 + bonus + rights
            if denominator <= 0:
                raise ValueError(f'{day} 无效送配股比例')
            after = (reference*10 - cash + rights*rights_price) / denominator
        elif category == 11:
            if bonus <= 0:
                raise ValueError(f'{day} 无效扩缩股比例')
            after = reference / bonus
        else:
            continue
        factor = after / reference
        if not math.isfinite(factor) or factor <= 0:
            raise ValueError(f'{day} 无效除权因子')
        boundaries[idx] = boundaries.get(idx, 1.) * factor
        reference, previous_index = after, idx
    factor = 1.
    result = [None] * len(bars)
    for idx in range(len(bars)-1, -1, -1):
        item = dict(bars[idx])
        for field in ('open','high','low','close'):
            item[field] = round(item[field]*factor, 8)
        result[idx] = item
        factor *= boundaries.get(idx, 1.)
    return result
