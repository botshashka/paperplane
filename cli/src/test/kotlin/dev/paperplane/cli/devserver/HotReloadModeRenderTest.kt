package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.ui.assertEmittedInOrder
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
