#!/bin/zsh
set -euo pipefail

REPO_ROOT="/Users/lifujian/cursorProjects/a-trend-data"
JAVA_DIR="${REPO_ROOT}/java"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Start daily fetch"
cd "${JAVA_DIR}"

# Compile and run daily updater.
mvn compile -q -DskipTests
mvn -q exec:java -Dexec.mainClass=DataUpdateCli "-Dexec.args=--repo-root ${REPO_ROOT}"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Daily fetch finished"
