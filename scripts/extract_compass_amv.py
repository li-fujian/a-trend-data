#!/usr/bin/env python3
"""Extract Compass (指南针) 0AMV 活跃市值 daily bars from local day.vdat.

Output goes to cache/compass/, not cache/kline/. Close Compass before running.
"""

from __future__ import annotations

import argparse
import json
import struct
from datetime import date
from pathlib import Path

SYMBOL = "0AMV"
NAME = "活跃市值"
SOURCE_CODE = "Z_SK0AMV"
REC_SIZE = 28
CODE_WIDTH = 32
MIN_CHUNK_BARS = 50
KNOWN_LAST = {
    "date": "2026-08-21",
    "open": 197547.9,
    "high": 202075.7,
    "low": 196247.5,
    "close": 196428.3,
}

DEFAULT_VDAT = Path(r"D:\Program Files\Compass\WavMain\ANALYSE\Data\ChinaStk\Z_SK\day.vdat")


def looks_date(value: int) -> bool:
    if not 19900101 <= value <= 20301231:
        return False
    year, month, day = value // 10000, (value // 100) % 100, value % 100
    try:
        date(year, month, day)
        return True
    except ValueError:
        return False


def ymd(value: int) -> str:
    return "%d-%02d-%02d" % (value // 10000, (value // 100) % 100, value % 100)


def parse_bar(record: bytes) -> dict | None:
    raw_date, open_, high, low, close, volume, amount = struct.unpack("<Iffffff", record)
    if not looks_date(raw_date) or close <= 0 or open_ <= 0 or high < low:
        return None
    if high + 1e-3 < max(open_, close) or low - 1e-3 > min(open_, close):
        return None
    return {
        "date": ymd(raw_date),
        "open": round(open_, 4),
        "high": round(high, 4),
        "low": round(low, 4),
        "close": round(close, 4),
        "volume": int(round(volume)),
        "amount": int(round(amount)),
    }


def extract_chunks(blob: bytes) -> list[list[dict]]:
    chunks: list[list[dict]] = []
    start = 0
    marker = SOURCE_CODE.encode("ascii")
    while True:
        idx = blob.find(marker, start)
        if idx < 0:
            break
        pos = idx + CODE_WIDTH
        chunk: list[dict] = []
        while pos + REC_SIZE <= len(blob):
            bar = parse_bar(blob[pos : pos + REC_SIZE])
            if bar is None:
                break
            if chunk and bar["date"] <= chunk[-1]["date"]:
                break
            chunk.append(bar)
            pos += REC_SIZE
        if len(chunk) >= MIN_CHUNK_BARS:
            chunks.append(chunk)
        start = idx + 1
    return chunks


def merge_chunks(chunks: list[list[dict]]) -> list[dict]:
    chunks = sorted(chunks, key=lambda c: c[0]["date"])
    bars: list[dict] = []
    for chunk in chunks:
        for bar in chunk:
            if bars and bar["date"] <= bars[-1]["date"]:
                raise SystemExit(
                    "date not increasing: %s after %s" % (bar["date"], bars[-1]["date"])
                )
            bars.append(bar)
    return bars


def validate(bars: list[dict]) -> None:
    if len(bars) < 1000:
        raise SystemExit("too few bars: %d" % len(bars))
    last = bars[-1]
    if last["date"] != KNOWN_LAST["date"]:
        # History may grow after the snapshot this script was written against.
        if last["date"] < KNOWN_LAST["date"]:
            raise SystemExit("last date %s is before known %s" % (last["date"], KNOWN_LAST["date"]))
    if last["date"] == KNOWN_LAST["date"]:
        for field in ("open", "high", "low", "close"):
            if abs(last[field] - KNOWN_LAST[field]) > 1.0:
                raise SystemExit("%s mismatch: %s vs %s" % (field, last[field], KNOWN_LAST[field]))


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract Compass 0AMV daily bars")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--vdat", type=Path, default=DEFAULT_VDAT)
    args = parser.parse_args()

    vdat = args.vdat
    if not vdat.is_file():
        raise SystemExit("day.vdat not found: %s (close Compass if the file is locked)" % vdat)

    blob = vdat.read_bytes()
    chunks = extract_chunks(blob)
    if not chunks:
        raise SystemExit("no 0AMV daily chunks found in %s" % vdat)
    bars = merge_chunks(chunks)
    validate(bars)

    out_dir = args.repo_root / "cache" / "compass"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / ("%s.json" % SYMBOL)
    payload = {
        "symbol": SYMBOL,
        "name": NAME,
        "source": "compass",
        "source_code": SOURCE_CODE,
        "kind": "market_indicator",
        "freq": "daily",
        "last_updated": bars[-1]["date"],
        "bar_count": len(bars),
        "bars": bars,
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote %s" % out)
    print("bars %d  %s -> %s" % (len(bars), bars[0]["date"], bars[-1]["date"]))
    print("last close %.4f  volume %s  amount %s" % (bars[-1]["close"], bars[-1]["volume"], bars[-1]["amount"]))


if __name__ == "__main__":
    main()
