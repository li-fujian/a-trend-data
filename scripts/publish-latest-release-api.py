#!/usr/bin/env python3
"""Publish dist/kline-latest.tar.zst to GitHub Release 'latest' via REST API."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

ASSET_NAME = "kline-latest.tar.zst"
RELEASE_TAG = "latest"
API = "https://api.github.com"


def request(
    method: str,
    url: str,
    token: str,
    data: bytes | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[int, bytes]:
    hdrs = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "a-trend-data-publish",
    }
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPO", "li-fujian/a-trend-data"))
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("GITHUB_TOKEN or GH_TOKEN required")

    archive = args.repo_root / "dist" / ASSET_NAME
    if not archive.is_file():
        raise SystemExit(f"Archive not found: {archive}")

    owner, name = args.repo.split("/", 1)
    base = f"{API}/repos/{owner}/{name}"

    status, body = request("GET", f"{base}/releases/tags/{RELEASE_TAG}", token)
    if status == 404:
        payload = json.dumps(
            {
                "tag_name": RELEASE_TAG,
                "name": "Latest K-line snapshot",
                "body": f"Rolling latest A-share qfq K-line bundle. Updated {date.today().isoformat()}.",
            }
        ).encode()
        status, body = request("POST", f"{base}/releases", token, payload, {"Content-Type": "application/json"})
        if status not in (200, 201):
            raise SystemExit(f"Create release failed ({status}): {body.decode(errors='replace')}")

    release = json.loads(body)
    release_id = release["id"]
    upload_url = release["upload_url"].replace("{?name,label}", "")

    for asset in release.get("assets", []):
        if asset["name"] == ASSET_NAME:
            request("DELETE", f"{base}/releases/assets/{asset['id']}", token)

    blob = archive.read_bytes()
    status, body = request(
        "POST",
        f"{upload_url}?name={ASSET_NAME}",
        token,
        blob,
        {"Content-Type": "application/octet-stream", "Content-Length": str(len(blob))},
    )
    if status not in (200, 201):
        raise SystemExit(f"Upload failed ({status}): {body.decode(errors='replace')}")

    status, body = request("GET", f"{base}/releases?per_page=100", token)
    if status == 200:
        for rel in json.loads(body):
            tag = rel["tag_name"]
            if tag != RELEASE_TAG:
                print(f"==> delete release {tag}")
                request("DELETE", f"{base}/releases/{rel['id']}", token)

    print(f"==> Done: {RELEASE_TAG}/{ASSET_NAME}")


if __name__ == "__main__":
    main()
