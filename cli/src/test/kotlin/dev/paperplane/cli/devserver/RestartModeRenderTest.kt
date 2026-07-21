package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.ui.assertEmittedInOrder
import dev.paperplane.cli.ui.assertSeparatorBetween
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Visual regression tests for [RestartMode]'s startup phase and rebuild iteration.
 *
 * Drives `runStartup` and `rebuild` directly with a [DevSessionFixture]-backed `DevSession` and a
 * [FakePaperServerManager], then asserts on the captured terminal output. The infinite main watch
 * loop and JVM shutdown hooks installed by `run()` are not exercised — those are not unit-testable.
 * The fix-recovery path is tested via a subclass that stubs `enterFixRecovery` to avoid blocking
 * forever on the file watcher.
 */
class RestartModeRenderTest {

  @TempDir lateinit var tempDir: File

  /**
   * Test subclass that stubs the fix-recovery loop so tests don't block on the real file watcher.
   * Records that the recovery path was entered; returns null (as if Ctrl+C) so `run()` exits
   * cleanly.
   */
  private class TestableRestartMode(
      session: DevSession,
      serverManager: FakePaperServerManager,
      var fixRecoveryEntered: Boolean = false,
  ) : RestartMode(session, serverManager) {
    override fun enterFixRecovery(): DevSession.RunningState? {
      fixRecoveryEntered = true
      return null
    }
  }

  // ── runStartup happy path ──────────────────────────────────────────

  @Test
  fun `happy startup emits build, server-ready, info section, and Watching footer`() {
    val fixture =
        DevSessionFixture(tempDir).withMetadata(pluginName = "TestPlugin", version = "1.0.0")
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

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
    // The build/server-ready group and the info group are separated by exactly one blank line
    // (the nextSection commit).
    fixture.terminal.assertSeparatorBetween("server ready", "Server:", blankLines = 1)
  }

  @Test
  fun `happy startup info lines reflect the project metadata`() {
    val fixture = DevSessionFixture(tempDir).withMetadata(pluginName = "Foo", version = "2.3.4")
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    mode.runStartup()

    assertTrue(fixture.terminal.writes.any { it.contains("Foo v2.3.4") })
    assertTrue(fixture.terminal.writes.any { it.contains("restart") })
  }

  // ── runStartup metadata-resolve failure ────────────────────────────

  @Test
  fun `metadata resolve failure returns Aborted with no Watching footer`() {
    val fixture = DevSessionFixture(tempDir)
    fixture.gradle.nextMetadata = null // resolveMetadata returns PluginNotApplied
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(
        fixture.terminal.writes.any { it.contains("Could not read project metadata") },
        "expected the metadata-resolution error",
    )
    // No Watching footer should be opened.
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── runStartup metadata-task compile failure → fix recovery ────────
  // `ppMetadata` transitively depends on `compileJava`, so a typo in user source breaks
  // metadata resolution before any build/server logic runs. Route to BuildFailed (→ fix-recovery)
  // instead of the Aborted "ppl init" hint.

  @Test
  fun `metadata task failure routes to BuildFailed and shows no plugin-not-applied hint`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextMetadataResult = MetadataResult.TaskFailed
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.BuildFailed, outcome)
    assertTrue(
        fixture.terminal.writes.any { it.contains("Build failed") },
        "expected 'Build failed' framing, got: ${fixture.terminal.writes}",
    )
    assertFalse(
        fixture.terminal.writes.any { it.contains("Could not read project metadata") },
        "TaskFailed must not show the plugin-not-applied hint",
    )
    assertFalse(
        fixture.terminal.writes.any { it.contains("ppl init") },
        "TaskFailed must not show the ppl init hint",
    )
    assertTrue(fixture.terminal.writes.any { it.contains("Waiting for changes") })
    assertFalse(
        mode.fixRecoveryEntered,
        "runStartup must not call enterFixRecovery internally; control returns to run()",
    )
  }

  // ── runStartup build failure → fix recovery ────────────────────────

  @Test
  fun `build failure during startup returns BuildFailed outcome`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextBuildResult = false
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.BuildFailed, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
    // runStartup must NOT enter fix recovery itself — the caller (run()) owns that handoff.
    assertFalse(
        mode.fixRecoveryEntered,
        "runStartup must not call enterFixRecovery internally; control returns to run()",
    )
  }

  // ── runStartup server-failed-to-start ──────────────────────────────

  @Test
  fun `server failed to start emits the failure line and bails`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server =
        FakePaperServerManager(
            fixture.ppDir,
            fixture.downloader,
            fixture.ui,
            readyResult = false, // waitForReady returns false → "Server failed to start"
        )
    val mode = TestableRestartMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Server failed to start") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── native deploy: jar into plugins/, no LoadRequest ───────────────
  // Restart is a "compatible with everything" mode: it drops the jar into plugins/ and lets Paper
  // load it natively. There is no companion host to send a LoadRequest to, so — unlike hot-reload —
  // startServerAndReport never stages, never sends a LoadRequest, and never awaits a load
  // result (so it can never return LoadFailed for restart).

  @Test
  fun `startup deploys the jar into plugins natively without staging or a load request`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    val outcome = mode.runStartup()

    assertInstanceOf(DevSession.StartupOutcome.Running::class.java, outcome)
    assertTrue(
        server.calls.contains("copyPluginToPluginsDir(test.jar)"),
        "restart must deploy the jar into plugins/; calls were ${server.calls}",
    )
    assertFalse(
        server.calls.any { it.startsWith("stagePlugin") },
        "restart must NOT stage the jar (that is hot-reload only); calls were ${server.calls}",
    )
    assertTrue(
        server.calls.contains("copyCompanion(depend=0,softdepend=0)"),
        "native deploy must not rewrite the companion's depends; calls were ${server.calls}",
    )
    assertTrue(
        server.sentLoadRequests.isEmpty(),
        "native modes must not send a LoadRequest",
    )
  }

  // ── rebuild happy path ─────────────────────────────────────────────

  @Test
  fun `rebuild emits build, ready, totalTime and returns Watching`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    // Drive the rebuild path directly with a fake metadata + paperJar.
    val metadata = fixture.gradle.nextMetadata!!
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    // Wrap the call in a phase so the spinner/redraw plumbing has a block to operate inside,
    // mirroring how runMainWatchLoop calls it inside `ui.phase { ... rebuild(...) }`.
    lateinit var result: dev.paperplane.cli.ui.TerminalUI.PhaseEnd
    fixture.ui.phase {
      val r = mode.rebuild(metadata, paperJar)
      result = r
      r
    }

    assertEquals(dev.paperplane.cli.ui.TerminalUI.PhaseEnd.Watching, result)
    fixture.terminal.assertEmittedInOrder(
        "Build succeeded",
        "Server ready",
        "total",
        "Watching for changes",
    )
    assertTrue(server.calls.contains("stop"))
    assertTrue(server.calls.any { it.startsWith("start(") })
    // The rebuild must deploy the fresh jar into plugins/ before restarting — otherwise the
    // restarted server boots without the user's new code.
    assertTrue(
        server.calls.contains("copyPluginToPluginsDir(test.jar)"),
        "rebuild must copy the fresh jar into plugins/; calls were ${server.calls}",
    )
    assertFalse(server.calls.any { it.startsWith("stagePlugin") })
  }

  // ── rebuild build failure ──────────────────────────────────────────

  @Test
  fun `rebuild build failure emits Build failed and returns Waiting`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextBuildResult = false
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableRestartMode(fixture.session, server)

    val metadata = fixture.gradle.nextMetadata!!
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    lateinit var result: dev.paperplane.cli.ui.TerminalUI.PhaseEnd
    fixture.ui.phase {
      val r = mode.rebuild(metadata, paperJar)
      result = r
      r
    }

    assertEquals(dev.paperplane.cli.ui.TerminalUI.PhaseEnd.Waiting, result)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
    assertTrue(fixture.terminal.writes.any { it.contains("Waiting for changes") })
    assertFalse(fixture.terminal.writes.any { it.contains("Server ready") })
  }

  // ── rebuild server-failed-to-start ─────────────────────────────────

  @Test
  fun `rebuild server failure emits Server failed to start and returns Waiting`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server =
        FakePaperServerManager(
            fixture.ppDir,
            fixture.downloader,
            fixture.ui,
            readyResult = false,
        )
    val mode = TestableRestartMode(fixture.session, server)

    val metadata = fixture.gradle.nextMetadata!!
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    lateinit var result: dev.paperplane.cli.ui.TerminalUI.PhaseEnd
    fixture.ui.phase {
      val r = mode.rebuild(metadata, paperJar)
      result = r
      r
    }

    assertEquals(dev.paperplane.cli.ui.TerminalUI.PhaseEnd.Waiting, result)
    assertTrue(fixture.terminal.writes.any { it.contains("Server failed to start") })
    assertTrue(fixture.terminal.writes.any { it.contains("Build succeeded") })
  }

  // ── server log interleaving above the pinned footer ────────────────

  @Test
  fun `simulated server logs render above the pinned Watching footer`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server =
        FakePaperServerManager(
            fixture.ppDir,
            fixture.downloader,
            fixture.ui,
            simulatedLogs =
                listOf(
                    "[INFO] Starting Paper server...",
                    "[INFO] Done (5.0s)! For help, type \"help\"",
                ),
        )
    val mode = TestableRestartMode(fixture.session, server)

    mode.runStartup()

    // Both server log lines should be in the output, interleaved with the build/info groups.
    assertTrue(fixture.terminal.writes.any { it.contains("Starting Paper server") })
    assertTrue(fixture.terminal.writes.any { it.contains("Done (5.0s)") })
  }
}
