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
# SMOKE_PAPER_VERSION pins the Paper version to scaffold against (default 1.21.11 — the newest
# release in the newest api-version line the CLI supports; keep it in step with
# WorldRefreshE2ETest's PPL_E2E_PAPER_VERSION default). Set it to "latest" to omit the pin and
# let the CLI resolve the newest supported Paper — the CI drift canary for the
# ReflectionProbe/CPCL integration.
#
# NOTE: "latest" is currently NOT the newest Paper. `resolveLatest()` filters to
# Versions.SUPPORTED_API_VERSIONS, which has no 26.x entry, so the canary resolves to the same
# 1.21.x line as the pin and cannot see the version family it was built to watch.
#
# Designed for CI: exits non-zero on any verification failure, prints the relevant log lines
# for diagnosis, and cleans up child processes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="$(mktemp -d)"
LOG_FILE="$WORK_DIR/dev.log"
TIMEOUT_SECONDS=120
PAPER_VERSION="${SMOKE_PAPER_VERSION:-1.21.11}"

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

# Inject a Brigadier lifecycle command into the scaffolded plugin BEFORE first boot. The probe
# (SMOKEBRIG-PRESENT / SMOKEBRIG-EXECUTED-*) self-dispatches from a +100-tick task because the
# script has no console into the Paper process. This is the semantic canary for the host's
# LifecycleEvents.COMMANDS re-fire: the ReflectionProbe already fails loudly when Paper RENAMES
# the internals, but only an end-to-end dispatch catches Paper changing their BEHAVIOR (command
# not registered, stale node executing old code, duplicate executions).
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
inject = marker + """
        try {
            this.getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
                event.registrar().register(
                        io.papermc.paper.command.brigadier.Commands.literal("smokebrig")
                                .executes(ctx -> {
                                    getLogger().info("SMOKEBRIG-EXECUTED-V1");
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .build());
            });
        } catch (Throwable t) {
            getLogger().info("SMOKEBRIG-REG-THREW: " + t);
        }
        org.bukkit.Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().info("SMOKEBRIG-PRESENT=" + (org.bukkit.Bukkit.getCommandMap().getCommand("smokebrig") != null));
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "smokebrig");
        }, 100L);"""
src = src.replace(marker, inject, 1)
p.write_text(src)
EOF

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

# 6. The Brigadier lifecycle command exists and executes — the host's COMMANDS re-fire worked
#    end-to-end. The plugin's probe logs at +100 ticks (~5s after enable), so poll.
brig_deadline=$(( $(date +%s) + 45 ))
until grep -qF "SMOKEBRIG-EXECUTED-V1" "$LOG_FILE"; do
  if grep -qF "SMOKEBRIG-REG-THREW" "$LOG_FILE"; then
    echo "FAIL: lifecycle COMMANDS handler registration threw."
    grep -F "SMOKEBRIG-REG-THREW" "$LOG_FILE"
    exit 1
  fi
  if [[ $(date +%s) -ge $brig_deadline ]]; then
    echo "FAIL: Brigadier lifecycle command never executed — the COMMANDS re-fire is broken."
    grep -F "SMOKEBRIG" "$LOG_FILE" || echo "  (no SMOKEBRIG lines at all)"
    tail -50 "$LOG_FILE"
    exit 1
  fi
  sleep 2
done
if ! grep -qF "SMOKEBRIG-PRESENT=true" "$LOG_FILE"; then
  echo "FAIL: /smokebrig absent from the command map after load."
  grep -F "SMOKEBRIG" "$LOG_FILE" || true
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
# onEnable string re-logs and proves the NEW code is live. The Brigadier handler's executed
# string flips V1 -> V2 so a stale pre-reload node executing old-classloader code is detectable
# as a second V1 line. MAIN_SRC was resolved (and validated) before first boot.
python3 - "$MAIN_SRC" <<'EOF'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()
marker = 'getLogger().info("SmokeTest enabled!");'
assert marker in src, "onEnable log line not found in " + sys.argv[1]
src = src.replace(marker, 'getLogger().info("SmokeTest enabled! v2");')
src = src.replace('SMOKEBRIG-EXECUTED-V1', 'SMOKEBRIG-EXECUTED-V2')
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

# 7. After the reload the NEW instance's Brigadier handler executes — and exactly once each way:
#    a second V1 execution means the stale pre-reload node survived teardown and ran
#    old-classloader code; more than one V2 means duplicate registration.
brig2_deadline=$(( $(date +%s) + 45 ))
until grep -qF "SMOKEBRIG-EXECUTED-V2" "$LOG_FILE"; do
  if [[ $(date +%s) -ge $brig2_deadline ]]; then
    echo "FAIL: reloaded Brigadier command never executed the NEW handler."
    grep -F "SMOKEBRIG" "$LOG_FILE" || true
    tail -50 "$LOG_FILE"
    exit 1
  fi
  sleep 2
done
v1_count=$(grep -cF "SMOKEBRIG-EXECUTED-V1" "$LOG_FILE" || true)
v2_count=$(grep -cF "SMOKEBRIG-EXECUTED-V2" "$LOG_FILE" || true)
if [[ "$v1_count" -ne 1 || "$v2_count" -ne 1 ]]; then
  echo "FAIL: expected exactly one V1 and one V2 execution, got V1=$v1_count V2=$v2_count —"
  echo "      stale command node or duplicate registration."
  grep -F "SMOKEBRIG" "$LOG_FILE"
  exit 1
fi
present_true_count=$(grep -cF "SMOKEBRIG-PRESENT=true" "$LOG_FILE" || true)
if [[ "$present_true_count" -ne 2 ]]; then
  echo "FAIL: /smokebrig must be present in the command map after BOTH loads (got $present_true_count/2)."
  grep -F "SMOKEBRIG-PRESENT" "$LOG_FILE" || true
  exit 1
fi

# 8. The reload was clean: no leak warnings, no reload failures.
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

# 9. Invariant #3 still holds after the reload: the rebuilt jar stays out of plugins/.
if [[ -f "$WORK_DIR/smoketest/.paperplane/server/plugins/smoketest-1.0.0.jar" ]]; then
  echo "FAIL: User JAR landed in plugins/ after the reload cycle."
  exit 1
fi

echo "==> All checks passed."
exit 0
