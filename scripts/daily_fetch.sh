#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${A_TREND_DATA_REPO_ROOT:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
JAVA_DIR="${REPO_ROOT}/java"
MVN_BIN="${MVN_BIN:-mvn}"
LOG_DIR="${REPO_ROOT}/logs/cron"
LOCK_FILE="${REPO_ROOT}/.daily-fetch.lock"

MODE="${A_TREND_FETCH_MODE:-incremental}"
INCREMENTAL_BARS="${A_TREND_INCREMENTAL_BARS:-420}"
MAX_FAILED_TO_PUBLISH="${A_TREND_MAX_FAILED_TO_PUBLISH:-20}"
MIN_FRESH_TO_PUBLISH="${A_TREND_MIN_FRESH_TO_PUBLISH:-1000}"
MIN_SLEEP_MS="${A_TREND_MIN_SLEEP_MS:-1200}"
MAX_SLEEP_MS="${A_TREND_MAX_SLEEP_MS:-2200}"
WORKERS="${A_TREND_WORKERS:-1}"

mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/daily-fetch-$(date '+%Y-%m-%d').log"

notify() {
  local status="$1"
  local message="$2"
  if [[ -n "${A_TREND_NOTIFY_WEBHOOK:-}" ]]; then
    curl -fsS -X POST \
      -H 'Content-Type: application/json' \
      -d "{\"status\":\"${status}\",\"message\":\"${message}\"}" \
      "${A_TREND_NOTIFY_WEBHOOK}" >/dev/null || true
  fi
}

run_update() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] Start daily fetch"
  echo "Repo root: ${REPO_ROOT}"
  echo "Mode: ${MODE}, incremental bars: ${INCREMENTAL_BARS}, max failed: ${MAX_FAILED_TO_PUBLISH}, min fresh: ${MIN_FRESH_TO_PUBLISH}, workers: ${WORKERS}"

  cd "${JAVA_DIR}"
  "${MVN_BIN}" -q -DskipTests compile
  "${MVN_BIN}" -q exec:java -Dexec.mainClass=DataUpdateCli \
    "-Dexec.args=--repo-root ${REPO_ROOT} --trading-days-only --mode ${MODE} --incremental-bars ${INCREMENTAL_BARS} --max-failed-to-publish ${MAX_FAILED_TO_PUBLISH} --min-fresh-to-publish ${MIN_FRESH_TO_PUBLISH} --min-sleep-ms ${MIN_SLEEP_MS} --max-sleep-ms ${MAX_SLEEP_MS} --workers ${WORKERS}"

  echo "[$(date '+%Y-%m-%d %H:%M:%S')] Daily fetch finished"
}

{
  flock -n 9 || {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Another daily fetch is running, skip"
    exit 0
  }

  if run_update; then
    notify "success" "a-trend-data daily fetch completed on $(hostname)"
  else
    code="$?"
    notify "failed" "a-trend-data daily fetch failed on $(hostname), exit=${code}"
    exit "${code}"
  fi
} 9>"${LOCK_FILE}" 2>&1 | tee -a "${LOG_FILE}"
