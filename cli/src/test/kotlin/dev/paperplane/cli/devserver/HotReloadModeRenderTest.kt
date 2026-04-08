package dev.paperplane.cli.devserver

import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.ui.assertEmittedInOrder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertFalse
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

  /** Test subclass that stubs the fix-recovery loop so build failures don't block forever. */
  private class TestableHotReloadMode(
      session: DevSession,
      serverManager: FakePaperServerManager,
      var fixRecoveryEntered: Boolean = false,
  ) : HotReloadMode(session, serverManager) {
    override fun enterFixRecovery(): Nothing {
      fixRecoveryEntered = true
      throw FixRecoveryEnteredSentinel
    }
  }

  private object FixRecoveryEnteredSentinel : RuntimeException("fix recovery entered (test escape)")

  // ── Happy startup ──────────────────────────────────────────────────

  @Test
  fun `happy startup emits build, server-ready, info section labelled hot-reload`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    val state = mode.runStartup(AtomicBoolean(false))

    assertNotNull(state)
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

    val shuttingDown = AtomicBoolean(false)
    val state = mode.runStartup(shuttingDown)

    assertNull(state)
    assertTrue(shuttingDown.get())
    assertTrue(fixture.terminal.writes.any { it.contains("PaperPlane Gradle plugin not found") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── Build failure → fix recovery ───────────────────────────────────

  @Test
  fun `build failure during startup transfers to fix recovery`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextBuildResult = false
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = TestableHotReloadMode(fixture.session, server)

    try {
      mode.runStartup(AtomicBoolean(false))
    } catch (_: RuntimeException) {
      // sentinel
    }

    assertTrue(mode.fixRecoveryEntered)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
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

    val state = mode.runStartup(AtomicBoolean(false))

    assertNull(state)
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

    mode.runStartup(AtomicBoolean(false))

    assertTrue(fixture.terminal.writes.any { it.contains("Starting Paper server") })
    assertTrue(fixture.terminal.writes.any { it.contains("Done (3.2s)") })
  }
}
