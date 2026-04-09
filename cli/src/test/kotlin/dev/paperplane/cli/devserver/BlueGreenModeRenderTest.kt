package dev.paperplane.cli.devserver

import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.testing.FakeVelocityDownloader
import dev.paperplane.cli.testing.FakeVelocityManager
import dev.paperplane.cli.ui.assertEmittedInOrder
import dev.paperplane.cli.ui.assertSeparatorBetween
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Visual regression tests for [BlueGreenMode]'s startup phase.
 *
 * Drives `runStartup` directly with a [DevSessionFixture]-backed `DevSession` plus fake
 * [FakePaperServerManager] (× 2 — server + swap), [FakeVelocityDownloader], and
 * [FakeVelocityManager]. The full rebuild + transfer + pre-warm cycle is not exercised here — it
 * depends on real ServerSync world cloning and a real pre-warm thread, both of which would need
 * their own fakes. The startup phase is the most user-visible rendering path; that's what's locked
 * in here.
 */
class BlueGreenModeRenderTest {

  @TempDir lateinit var tempDir: File

  /**
   * Test subclass that stubs the fix-recovery loop so tests don't block on the real file watcher.
   */
  private class TestableBlueGreenMode(
      session: DevSession,
      servers: Map<Slot, dev.paperplane.cli.server.PaperServerManager>,
      velocityDownloader: FakeVelocityDownloader,
      velocityManager: FakeVelocityManager,
      var fixRecoveryEntered: Boolean = false,
  ) :
      BlueGreenMode(
          session = session,
          servers = servers,
          velocityDownloader = velocityDownloader,
          velocityManager = velocityManager,
      ) {
    override fun enterFixRecovery(): DevSession.RunningState? {
      fixRecoveryEntered = true
      return null
    }
  }

  private fun newMode(
      readyResult: Boolean = true,
      buildResult: Boolean = true,
      proxyReadyResult: Boolean = true,
      simulatedServerLogs: List<String> = emptyList(),
      simulatedProxyLogs: List<String> = emptyList(),
  ): Triple<TestableBlueGreenMode, DevSessionFixture, FakeVelocityManager> {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    fixture.gradle.nextBuildResult = buildResult
    val serverA =
        FakePaperServerManager(
            File(fixture.ppDir, "server"),
            fixture.downloader,
            fixture.ui,
            readyResult = readyResult,
            simulatedLogs = simulatedServerLogs,
        )
    val serverB =
        FakePaperServerManager(
            File(fixture.ppDir, "server-swap"),
            fixture.downloader,
            fixture.ui,
            readyResult = readyResult,
        )
    val proxy =
        FakeVelocityManager(
            File(fixture.ppDir, "proxy"),
            fixture.ui,
            readyResult = proxyReadyResult,
            simulatedLogs = simulatedProxyLogs,
        )
    val downloader = FakeVelocityDownloader(File(fixture.ppDir, "cache"))
    val mode =
        TestableBlueGreenMode(
            session = fixture.session,
            servers =
                mapOf(BlueGreenMode.Slot.SERVER to serverA, BlueGreenMode.Slot.SWAP to serverB),
            velocityDownloader = downloader,
            velocityManager = proxy,
        )
    return Triple(mode, fixture, proxy)
  }

  // ── Happy startup ──────────────────────────────────────────────────

  @Test
  fun `happy startup emits build, proxy ready, server ready, info section, Watching footer`() {
    val (mode, fixture, _) = newMode()
    val outcome = mode.runStartup(AtomicBoolean(false))

    assertInstanceOf(DevSession.StartupOutcome.Running::class.java, outcome)
    fixture.terminal.assertEmittedInOrder(
        "Reading project metadata",
        "Building",
        "Build succeeded",
        "Downloading Velocity",
        "Velocity proxy ready",
        "Starting Paper",
        "Paper",
        "server ready",
        "Server:",
        "Plugin:",
        "Mode:",
        "Watching for changes",
    )
    // Build/server-ready group separated from info group by exactly one blank line.
    fixture.terminal.assertSeparatorBetween("server ready", "Server:", blankLines = 1)
  }

  @Test
  fun `happy startup info line shows blue-green mode label and proxy address`() {
    val (mode, fixture, _) = newMode()
    mode.runStartup(AtomicBoolean(false))

    assertTrue(fixture.terminal.writes.any { it.contains("blue-green") })
    assertTrue(fixture.terminal.writes.any { it.contains("via proxy") })
  }

  @Test
  fun `happy startup configures velocity proxy with the right ports`() {
    val (mode, _, proxy) = newMode()
    mode.runStartup(AtomicBoolean(false))

    assertTrue(
        proxy.calls.any {
          it.startsWith("configure(server=${BlueGreenMode.SERVER_A_PORT}") &&
              it.contains("swap=${BlueGreenMode.SERVER_B_PORT}") &&
              it.contains("proxy=25565")
        },
        "expected proxy.configure() call with the correct ports, got: ${proxy.calls}",
    )
    assertTrue(proxy.calls.any { it.startsWith("start(") })
    assertTrue(proxy.calls.contains("waitForReady"))
  }

  // ── Metadata resolve failure ───────────────────────────────────────

  @Test
  fun `metadata resolve failure aborts before touching velocity`() {
    val (m, f, p) = newMode().also { it.second.gradle.nextMetadata = null }
    val outcome = m.runStartup(AtomicBoolean(false))

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(f.terminal.writes.any { it.contains("Could not read project metadata") })
    assertFalse(p.calls.any { it.startsWith("configure") })
  }

  // ── Build failure → fix recovery ───────────────────────────────────

  @Test
  fun `build failure during startup returns BuildFailed without touching the proxy`() {
    val (mode, fixture, proxy) = newMode(buildResult = false)

    val outcome = mode.runStartup(AtomicBoolean(false))

    assertEquals(DevSession.StartupOutcome.BuildFailed, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
    // Proxy should not have been configured because the build failed before that step.
    assertFalse(proxy.calls.any { it.startsWith("configure") })
    assertFalse(
        mode.fixRecoveryEntered,
        "runStartup must not call enterFixRecovery internally; control returns to run()",
    )
  }

  // ── Proxy failed to start ──────────────────────────────────────────

  @Test
  fun `proxy failed to start emits failure line and bails before paper server`() {
    val (mode, fixture, proxy) = newMode(proxyReadyResult = false)

    val outcome = mode.runStartup(AtomicBoolean(false))

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Proxy failed to start") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
    assertTrue(proxy.calls.contains("waitForReady"))
  }

  // ── Paper server failed to start ───────────────────────────────────

  @Test
  fun `paper server failed to start emits failure line after proxy is up`() {
    val (mode, fixture, _) = newMode(readyResult = false)

    val outcome = mode.runStartup(AtomicBoolean(false))

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    // Proxy did come up successfully.
    assertTrue(fixture.terminal.writes.any { it.contains("Velocity proxy ready") })
    // But the paper server failed.
    assertTrue(fixture.terminal.writes.any { it.contains("Server failed to start") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── Server log interleaving ────────────────────────────────────────

  @Test
  fun `simulated server logs render alongside the startup output`() {
    val (mode, fixture, _) =
        newMode(
            simulatedServerLogs =
                listOf(
                    "[INFO] Starting Paper server...",
                    "[INFO] Done (5.5s)! For help, type \"help\"",
                ),
        )
    mode.runStartup(AtomicBoolean(false))

    assertTrue(fixture.terminal.writes.any { it.contains("Starting Paper server") })
    assertTrue(fixture.terminal.writes.any { it.contains("Done (5.5s)") })
  }
}
