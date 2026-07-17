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
# SMOKE_PAPER_VERSION pins the Paper version to scaffold against (default 1.21.4). Set it to
# "latest" to omit the pin and let the CLI resolve the newest supported Paper — the CI drift
# canary for the ReflectionProbe/CPCL integration.
#
# Designed for CI: exits non-zero on any verification failure, prints the relevant log lines
# for diagnosis, and cleans up child processes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="$(mktemp -d)"
LOG_FILE="$WORK_DIR/dev.log"
TIMEOUT_SECONDS=120
PAPER_VERSION="${SMOKE_PAPER_VERSION:-1.21.4}"

cleanup() {
  if [[ -n "${DEV_PID:-}" ]] && kill -0 "$DEV_PID" 2>/dev/null; then
    kill -KILL "$DEV_PID" 2>/dev/null || true
  fi
  pkill -KILL -f "java.*paper-[0-9]" 2>/dev/null || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "==> Building CLI fat JAR and publishing the Gradle plugin to mavenLocal..."
# The scaffolded project applies the dev.paperplane Gradle plugin, which `ppl create` wires up to
# resolve from mavenLocal (a pluginManagement block is only emitted when the plugin is present
# there). A fresh CI runner has an empty mavenLocal, so publish it or the first build fails with
# "Plugin [id: 'dev.paperplane'] was not found".
"$REPO_ROOT/gradlew" :cli:shadowJar :gradle-plugin:publishToMavenLocal

echo "==> Scaffolding test plugin in $WORK_DIR (Paper: $PAPER_VERSION)..."
cd "$WORK_DIR"
if [[ "$PAPER_VERSION" == "latest" ]]; then
  "$REPO_ROOT/ppl" create smoketest -n SmokeTest -a ci
else
  "$REPO_ROOT/ppl" create smoketest -n SmokeTest -a ci --paper "$PAPER_VERSION"
fi
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

# 5. Cross-plugin lookup works: the host dual-writes into Paper's real lookupNames map and
#    verifies getPlugin(name) resolves the dev-loaded plugin (the Commit-2 canary line).
if ! grep -qF "Cross-plugin lookup verified" "$LOG_FILE"; then
  echo "FAIL: 'Cross-plugin lookup verified' absent — getPlugin(name) lookup is broken."
  grep -F "Cross-plugin lookup" "$LOG_FILE" || true
  tail -50 "$LOG_FILE"
  exit 1
fi

echo "==> Exercising a hot-reload cycle..."

# The file watcher takes its baseline snapshot only after the CLI prints the post-startup
# ribbon — an edit made before that gets folded into the baseline and never fires. Wait for
# the ribbon, then pad a few seconds.
mode_deadline=$(( $(date +%s) + 60 ))
until grep -qF "Mode:" "$LOG_FILE"; do
  if [[ $(date +%s) -ge $mode_deadline ]]; then
    echo "FAIL: post-startup ribbon never appeared — watcher never started."
    tail -50 "$LOG_FILE"
    exit 1
  fi
  sleep 1
done
sleep 6

# Structural edit (new method) forces a full reload rather than a hotswap, so the changed
# onEnable string re-logs and proves the NEW code is live.
MAIN_SRC="$(find src/main/java -name "*.java" | head -1)"
if [[ -z "$MAIN_SRC" ]]; then
  echo "FAIL: scaffolded main class not found under src/main/java."
  exit 1
fi
python3 - "$MAIN_SRC" <<'EOF'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()
marker = 'getLogger().info("SmokeTest enabled!");'
assert marker in src, "onEnable log line not found in " + sys.argv[1]
src = src.replace(marker, 'getLogger().info("SmokeTest enabled! v2");')
src = src.replace("@Override\n    public void onEnable() {",
                  "// smoke-reload\n    public void smokeReload() { }\n\n    @Override\n    public void onEnable() {", 1)
p.write_text(src)
EOF

# Poll for the CLI's reload-success ribbon AND the changed onEnable string. Non-TTY output
# flushes in blocks, so don't assume ordering between server log lines and CLI ribbons.
# Re-touch the source every 45s in case the edit landed before the watcher's baseline.
reload_deadline=$(( $(date +%s) + 120 ))
next_touch=$(( $(date +%s) + 45 ))
until grep -qF "SmokeTest enabled! v2" "$LOG_FILE" && grep -qF "Plugin reloaded" "$LOG_FILE"; do
  if [[ $(date +%s) -ge $reload_deadline ]]; then
    echo "FAIL: reload cycle never completed (edited code not live within 120s)."
    tail -80 "$LOG_FILE"
    exit 1
  fi
  if ! kill -0 "$DEV_PID" 2>/dev/null; then
    echo "FAIL: ppl dev exited during the reload cycle."
    tail -80 "$LOG_FILE"
    exit 1
  fi
  if [[ $(date +%s) -ge $next_touch ]]; then
    echo "    (re-touching $MAIN_SRC — watcher may have missed the edit)"
    touch "$MAIN_SRC"
    next_touch=$(( $(date +%s) + 45 ))
  fi
  sleep 2
done

# 6. The reload was clean: no leak warnings, no reload failures.
if grep -qF "leaked" "$LOG_FILE"; then
  echo "FAIL: reload leaked memory."
  grep -F "leaked" "$LOG_FILE"
  exit 1
fi
if grep -qF "Reload failed" "$LOG_FILE"; then
  echo "FAIL: a reload failed."
  grep -F "Reload failed" "$LOG_FILE"
  exit 1
fi

# 7. Invariant #3 still holds after the reload: the rebuilt jar stays out of plugins/.
if [[ -f "$WORK_DIR/smoketest/.paperplane/server/plugins/smoketest-1.0.0.jar" ]]; then
  echo "FAIL: User JAR landed in plugins/ after the reload cycle."
  exit 1
fi

echo "==> All checks passed."
exit 0
