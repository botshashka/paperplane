#!/bin/bash
# Real-server smoke test for the wrapper-host architecture.
#
# Boots a real Paper server via `ppl dev` against a freshly-scaffolded plugin and asserts that
# the companion's host successfully loads the inner plugin. This is the canary for the
# ReflectionProbe — if it works against the running Paper version, our reflection points are
# valid; if not, the probe surfaces a clear "Unsupported Paper version" error.
#
# Run locally with:
#   .github/scripts/smoke-test.sh
#
# Designed for CI: exits non-zero on any verification failure, prints the relevant log lines
# for diagnosis, and cleans up child processes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="$(mktemp -d)"
LOG_FILE="$WORK_DIR/dev.log"
TIMEOUT_SECONDS=120

cleanup() {
  if [[ -n "${DEV_PID:-}" ]] && kill -0 "$DEV_PID" 2>/dev/null; then
    kill -KILL "$DEV_PID" 2>/dev/null || true
  fi
  pkill -KILL -f "java.*paper-1.21" 2>/dev/null || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "==> Building CLI fat JAR..."
"$REPO_ROOT/gradlew" :cli:shadowJar

echo "==> Scaffolding test plugin in $WORK_DIR..."
cd "$WORK_DIR"
"$REPO_ROOT/ppl" create smoketest -n SmokeTest -a ci --paper 1.21.4
cd "$WORK_DIR/smoketest"

echo "==> Starting ppl dev (timeout ${TIMEOUT_SECONDS}s)..."
"$REPO_ROOT/ppl" dev > "$LOG_FILE" 2>&1 &
DEV_PID=$!

echo "==> Waiting for host load..."
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while [[ $(date +%s) -lt $deadline ]]; do
  if grep -qF "Enabling Smoketest" "$LOG_FILE" 2>/dev/null \
     || grep -qF "[Smoketest] Enabling SmokeTest" "$LOG_FILE" 2>/dev/null; then
    break
  fi
  if grep -qF "UnsupportedPaperVersionException" "$LOG_FILE" 2>/dev/null; then
    echo "FAIL: ReflectionProbe rejected this Paper version."
    tail -50 "$LOG_FILE"
    exit 1
  fi
  if ! kill -0 "$DEV_PID" 2>/dev/null; then
    echo "FAIL: ppl dev exited before host load."
    tail -50 "$LOG_FILE"
    exit 1
  fi
  sleep 2
done

echo "==> Verifying invariants..."

# 1. Companion announced host-ready.
if ! grep -qF "PaperPlane companion enabled" "$LOG_FILE"; then
  echo "FAIL: companion-enabled marker absent — companion onEnable likely threw."
  tail -50 "$LOG_FILE"
  exit 1
fi

# 2. Inner plugin's onEnable ran.
if ! grep -qE "Enabling Smoketest|\[Smoketest\] Enabling" "$LOG_FILE"; then
  echo "FAIL: Inner plugin's onEnable never fired."
  tail -50 "$LOG_FILE"
  exit 1
fi

# 3. User JAR is staged outside plugins/ (the central architectural invariant).
if [[ -f "$WORK_DIR/smoketest/.paperplane/server/plugins/smoketest-1.0.0.jar" ]]; then
  echo "FAIL: User JAR landed in plugins/ — Paper would auto-load it. The flip didn't take."
  exit 1
fi
if [[ ! -f "$WORK_DIR/smoketest/.paperplane/server/.paperplane/staged/smoketest-1.0.0.jar" ]]; then
  echo "FAIL: Staged JAR missing from .paperplane/staged/."
  ls -la "$WORK_DIR/smoketest/.paperplane/server/.paperplane/" || true
  exit 1
fi

# 4. CLI consumed the load result and reported success. The CLI consumes (reads + deletes) the
#    load-complete flag, so assert its success ribbon in the dev log rather than the flag's
#    on-disk presence. The ribbon is buffered in non-TTY mode and flushes shortly AFTER the
#    server-side enable line the main wait loop keys on, so poll briefly instead of grepping once.
ribbon_deadline=$(( $(date +%s) + 30 ))
until grep -qF "Plugin loaded" "$LOG_FILE"; do
  if [[ $(date +%s) -ge $ribbon_deadline ]]; then
    echo "FAIL: CLI never reported 'Plugin loaded' — initial load result was not consumed."
    tail -50 "$LOG_FILE"
    exit 1
  fi
  sleep 1
done

echo "==> All checks passed."
exit 0
