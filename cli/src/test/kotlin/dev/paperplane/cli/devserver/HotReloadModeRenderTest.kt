package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import dev.paperplane.cli.ui.assertEmittedInOrder
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Visual regression tests for [HotReloadMode]'s startup phase.
 *
 * Drives `runStartup` directly with a [DevSessionFixture]-backed `DevSession` and a
 * [FakePaperServerManager]. The HMR rebuild path (rebuild → triggerReload → strategy ladder) is not
 * exercised here — it requires a real BuildSnapshot for class-change detection. The HMR strategy
 * logic itself is covered by the existing `HmrReloadFlowTest` and `HotReloadTest`. This file
 * focuses on the rendering layer of startup.
 */
class HotReloadModeRenderTest {

  @TempDir lateinit var tempDir: File

  /**
   * Test subclass that stubs the fix-recovery loop so tests don't block on the real file watcher.
   * Records that recovery was entered; returns null (as if Ctrl+C) so `run()` exits cleanly.
   */
  private class TestableHotReloadMode(
      session: DevSession,
      serverManager: FakePaperServerManager,
      var fixRecoveryEntered: Boolean = false,
  ) : HotReloadMode(session, serverManager) {
    override fun enterFixRecovery(): DevSession.RunningState? {
      fixRecoveryEntered = true
      return null
    }
  }

  // ── Happy startup ──────────────────────────────────────────────────

  @Test
  fun `happy startup emits build, server-ready, info section labelled hot-reload`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertInstanceOf(DevSession.StartupOutcome.Running::class.java, outcome)
    fixture.terminal.assertEmittedInOrder(
        "Reading project metadata",
        "Building",
        "Build succeeded",
        "Paper",
        "server ready",
        "Server:",
        "Plugin:",
        "Mode:",
        "Watching for changes",
    )
    // Mode label is hot-reload (or hot-reload (enhanced — JBR)) — both contain "hot-reload".
    assertTrue(fixture.terminal.writes.any { it.contains("hot-reload") })
  }

  // ── Metadata resolve failure ───────────────────────────────────────

  @Test
  fun `metadata resolve failure aborts with no Watching footer`() {
    val fixture = DevSessionFixture(tempDir)
    fixture.gradle.nextMetadata = null
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Could not read project metadata") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── Metadata task compile failure → fix recovery ──────────────────

  @Test
  fun `metadata task failure routes to BuildFailed in hot-reload mode`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextMetadataResult = MetadataResult.TaskFailed
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.BuildFailed, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
    assertFalse(fixture.terminal.writes.any { it.contains("Could not read project metadata") })
    assertFalse(
        mode.fixRecoveryEntered,
        "runStartup must not call enterFixRecovery internally; control returns to run()",
    )
  }

  // ── Build failure → fix recovery ───────────────────────────────────

  @Test
  fun `build failure during startup returns BuildFailed outcome`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextBuildResult = false
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.BuildFailed, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
    assertFalse(
        mode.fixRecoveryEntered,
        "runStartup must not call enterFixRecovery internally; control returns to run()",
    )
  }

  // ── Server failed to start ─────────────────────────────────────────

  @Test
  fun `server failed to start emits the failure line and bails`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server =
        FakePaperServerManager(
            fixture.ppDir,
            fixture.downloader,
            fixture.ui,
            readyResult = false,
        )
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Server failed to start") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── Initial load result branches ───────────────────────────────────
  // A failed initial *load* is recoverable (the fix is a source/config edit), so it must route to
  // StartupOutcome.LoadFailed → fix recovery with a "Waiting for changes..." footer — not abort
  // `ppl dev` — mirroring how an initial build failure behaves.

  @Test
  fun `failed initial load routes to LoadFailed, stops the server, and waits for changes`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.Failed("plugin.yml not found", null)
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.LoadFailed, outcome)
    assertTrue(
        fixture.terminal.writes.any { it.contains("Plugin failed to load: plugin.yml not found") }
    )
    // The diagnosis hint points at the plugin.yml source of truth.
    assertTrue(
        fixture.terminal.writes.any { it.contains("paperplane { } block in build.gradle.kts") },
        "a plugin.yml failure should hint at the paperplane { } block",
    )
    assertTrue(fixture.terminal.writes.any { it.contains("Waiting for changes") })
    assertTrue(
        server.calls.contains("stop"),
        "a rejected load must stop the just-started server, not leave it running unattended",
    )
  }

  @Test
  fun `timed-out initial load routes to LoadFailed, stops the server, and waits for changes`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.TimedOut
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.LoadFailed, outcome)
    assertTrue(
        fixture.terminal.writes.any { it.contains("Timed out waiting for the plugin to load") }
    )
    assertTrue(fixture.terminal.writes.any { it.contains("never reported back") })
    assertTrue(fixture.terminal.writes.any { it.contains("Waiting for changes") })
    assertTrue(server.calls.contains("stop"))
  }

  @Test
  fun `server exit during initial load routes to LoadFailed and waits for changes`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.ServerExited
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.LoadFailed, outcome)
    assertTrue(
        fixture.terminal.writes.any { it.contains("Server exited while loading the plugin") }
    )
    assertTrue(fixture.terminal.writes.any { it.contains("static") && it.contains("onEnable") })
    assertTrue(fixture.terminal.writes.any { it.contains("Waiting for changes") })
  }

  // ── Fix-recovery restart preserves hot-reload wiring ───────────────
  // Regression: HotReloadMode's fix-recovery restart previously called startServerAndReport with
  // default args, silently dropping hotReload=true / JBR java / the redefinition agent. The
  // recovered server must start with the same hot-reload wiring the initial startup uses.

  @Test
  fun `fix-recovery restart preserves the hot-reload agent wiring`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)
    val metadata = fixture.gradle.nextMetadata!!
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    var result: DevSession.RunningState? = null
    fixture.ui.phase {
      result = mode.startAfterFix(DevSession.FixAttempt.Success(metadata, paperJar))
      PhaseEnd.None
    }

    // The recovered server must start with hotReload=true, not the default false.
    assertTrue(
        server.calls.any { it.startsWith("start(") && it.contains("hotReload=true") },
        "recovered server must keep the hot-reload agent wiring, got: ${server.calls}",
    )
    assertNotNull(result, "a successful recovered load must hand back a RunningState")
    assertTrue(fixture.terminal.writes.any { it.contains("Plugin loaded") })
  }

  @Test
  fun `fix-recovery restart returns null when the load still fails`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.Failed("still broken", null)
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)
    val metadata = fixture.gradle.nextMetadata!!
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    var result: DevSession.RunningState? = null
    fixture.ui.phase {
      result = mode.startAfterFix(DevSession.FixAttempt.Success(metadata, paperJar))
      PhaseEnd.None
    }

    assertNull(result, "a still-failing load must keep fix recovery waiting (null handoff)")
  }

  // ── Stale protocol flags are cleared before start ──────────────────

  @Test
  fun `stale protocol flags from a crashed session are cleared before the server starts`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val ppDir = File(server.serverDir, ".paperplane").apply { mkdirs() }
    val stale =
        listOf("load-request.json", "load-complete", "load-failed", "companion-error").map {
          File(ppDir, it).apply { writeText("stale") }
        }
    val mode = TestableHotReloadMode(fixture.session, server)

    mode.runStartup()

    for (file in stale) {
      // startServerAndReport legitimately writes a fresh load-request.json after clearing, so
      // assert the stale CONTENT is gone rather than the file: nothing left over may be consumed.
      assertFalse(
          file.exists() && file.readText() == "stale",
          "${file.name} must be cleared so it can't be consumed as fresh",
      )
    }
  }

  // ── Rebuild reload-result branches (waitAndReport) ─────────────────

  @Test
  fun `failed reload renders the host message and keeps watching`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.Failed("onEnable threw", null)
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val end = mode.waitAndReport(fixture.gradle.nextMetadata!!, System.currentTimeMillis(), "r1")

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(fixture.terminal.writes.any { it.contains("Reload failed: onEnable threw") })
  }

  @Test
  fun `timed-out reload renders the old-plugin warning and keeps watching`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.TimedOut
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val end = mode.waitAndReport(fixture.gradle.nextMetadata!!, System.currentTimeMillis(), "r1")

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(
        fixture.terminal.writes.any {
          it.contains("Hot-reload failed (server still running with old plugin)")
        }
    )
  }

  @Test
  fun `server exit during reload renders it and waits for changes`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.loadWaitResult = LoadWaitResult.ServerExited
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val end = mode.waitAndReport(fixture.gradle.nextMetadata!!, System.currentTimeMillis(), "r1")

    assertEquals(PhaseEnd.Waiting, end)
    assertTrue(fixture.terminal.writes.any { it.contains("Server process exited during reload") })
  }

  // ── Server log interleaving ────────────────────────────────────────

  @Test
  fun `simulated server logs render alongside the startup output`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server =
        FakePaperServerManager(
            fixture.ppDir,
            fixture.downloader,
            fixture.ui,
            simulatedLogs =
                listOf(
                    "[INFO] Starting Paper server...",
                    "[INFO] Done (3.2s)! For help, type \"help\"",
                ),
        )
    val mode = TestableHotReloadMode(fixture.session, server)

    mode.runStartup()

    assertTrue(fixture.terminal.writes.any { it.contains("Starting Paper server") })
    assertTrue(fixture.terminal.writes.any { it.contains("Done (3.2s)") })
  }
}
