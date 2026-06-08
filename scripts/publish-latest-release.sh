#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo-root)
      REPO_ROOT="$2"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$REPO_ROOT" ]]; then
  REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fi

ASSET_NAME="kline-latest.tar.zst"
RELEASE_TAG="latest"
DIST_DIR="${REPO_ROOT}/dist"
ARCHIVE_PATH="${DIST_DIR}/${ASSET_NAME}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd tar
require_cmd zstd
require_cmd gh

if [[ ! -d "${REPO_ROOT}/cache/kline" ]]; then
  echo "cache/kline not found under ${REPO_ROOT}" >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
cd "$REPO_ROOT"

echo "==> Packaging ${ARCHIVE_PATH}"
rm -f "$ARCHIVE_PATH"
tar -C "$REPO_ROOT" -cf - cache/kline config/stock-universe.json logs/fetch-log.json \
  | zstd -T0 -19 -o "$ARCHIVE_PATH"

echo "==> Verifying archive"
zstd -q -t "$ARCHIVE_PATH"

echo "==> Ensuring release ${RELEASE_TAG}"
if ! gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
  gh release create "$RELEASE_TAG" \
    --title "Latest K-line snapshot" \
    --notes "Rolling latest A-share qfq K-line bundle. Updated $(date -u +%Y-%m-%d)."
fi

echo "==> Uploading asset (clobber)"
gh release upload "$RELEASE_TAG" "$ARCHIVE_PATH" --clobber

echo "==> Pruning old releases (keep only ${RELEASE_TAG})"
mapfile -t OLD_TAGS < <(gh release list --limit 200 --json tagName -q '.[].tagName')
for tag in "${OLD_TAGS[@]}"; do
  if [[ "$tag" != "$RELEASE_TAG" ]]; then
    echo "    delete ${tag}"
    gh release delete "$tag" --yes --cleanup-tag
  fi
done

echo "==> Done: ${RELEASE_TAG}/${ASSET_NAME}"
