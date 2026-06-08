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

require_cmd gh
require_cmd zstd
require_cmd tar

mkdir -p "$DIST_DIR"

echo "==> Downloading ${RELEASE_TAG}/${ASSET_NAME}"
gh release download "$RELEASE_TAG" \
  --pattern "$ASSET_NAME" \
  --dir "$DIST_DIR" \
  --clobber

echo "==> Verifying archive"
zstd -q -t "$ARCHIVE_PATH"

echo "==> Extracting to ${REPO_ROOT}"
zstd -d -c "$ARCHIVE_PATH" | tar -xf - -C "$REPO_ROOT"

echo "==> Done"
