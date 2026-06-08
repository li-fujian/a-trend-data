#!/usr/bin/env python3
"""Create kline-latest.tar.zst from local cache (no zstd CLI required)."""

from __future__ import annotations

import argparse
import tarfile
import tempfile
from pathlib import Path

import zstandard as zstd

ASSET_NAME = "kline-latest.tar.zst"
MEMBERS = ("cache/kline", "config/stock-universe.json", "logs/fetch-log.json")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    dist = root / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    out = dist / ASSET_NAME

    for member in MEMBERS:
        if not (root / member).exists():
            raise SystemExit(f"Missing required path: {root / member}")

    with tempfile.NamedTemporaryFile(suffix=".tar", delete=False) as tmp:
        tar_path = Path(tmp.name)

    try:
        print("==> Building tar...")
        with tarfile.open(tar_path, mode="w") as tar:
            for member in MEMBERS:
                tar.add(root / member, arcname=member, recursive=True)

        print("==> Compressing with zstd...")
        cctx = zstd.ZstdCompressor(level=19, threads=0)
        with tar_path.open("rb") as src, out.open("wb") as dst:
            cctx.copy_stream(src, dst)
        print("==> Verifying zstd frame...")
        dctx = zstd.ZstdDecompressor()
        with out.open("rb") as src, dctx.stream_reader(src) as reader:
            while reader.read(1_048_576):
                pass
        print(f"==> Wrote {out} ({out.stat().st_size / 1_048_576:.1f} MiB)")
    finally:
        tar_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
