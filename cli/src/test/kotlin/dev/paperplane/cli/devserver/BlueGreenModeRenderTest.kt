package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.testing.FakeVelocityDownloader
import dev.paperplane.cli.testing.FakeVelocityManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
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
 * Visual regression tests for [BlueGreenMode]'s startup phase.
 *
 * Drives `runStartup` (and `rebuild` for the deploy/transfer/pre-warm cycle) directly with a
 * [DevSessionFixture]-backed `DevSession` plus fake [FakePaperServerManager] (× 2 — server + swap),
 * [FakeVelocityDownloader], and [FakeVelocityManager].
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
    val outcome = mode.runStartup()

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
    mode.runStartup()

    assertTrue(fixture.terminal.writes.any { it.contains("blue-green") })
    assertTrue(fixture.terminal.writes.any { it.contains("via proxy") })
  }

  @Test
  fun `happy startup configures velocity proxy with the right ports`() {
    val (mode, _, proxy) = newMode()
    mode.runStartup()

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
    val outcome = m.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(f.terminal.writes.any { it.contains("Could not read project metadata") })
    assertFalse(p.calls.any { it.startsWith("configure") })
  }

  // ── Metadata task compile failure → fix recovery ──────────────────

  @Test
  fun `metadata task failure routes to BuildFailed without touching the proxy`() {
    val (mode, fixture, proxy) =
        newMode().also { it.second.gradle.nextMetadataResult = MetadataResult.TaskFailed }
    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.BuildFailed, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Build failed") })
    assertFalse(fixture.terminal.writes.any { it.contains("Could not read project metadata") })
    assertFalse(
        proxy.calls.any { it.startsWith("configure") },
        "metadata-task failure must short-circuit before proxy configure",
    )
    assertFalse(
        mode.fixRecoveryEntered,
        "runStartup must not call enterFixRecovery internally; control returns to run()",
    )
  }

  // ── Build failure → fix recovery ───────────────────────────────────

  @Test
  fun `build failure during startup returns BuildFailed without touching the proxy`() {
    val (mode, fixture, proxy) = newMode(buildResult = false)

    val outcome = mode.runStartup()

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

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    assertTrue(fixture.terminal.writes.any { it.contains("Proxy failed to start") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
    assertTrue(proxy.calls.contains("waitForReady"))
  }

  // ── Paper server failed to start ───────────────────────────────────

  @Test
  fun `paper server failed to start emits failure line after proxy is up`() {
    val (mode, fixture, _) = newMode(readyResult = false)

    val outcome = mode.runStartup()

    assertEquals(DevSession.StartupOutcome.Aborted, outcome)
    // Proxy did come up successfully.
    assertTrue(fixture.terminal.writes.any { it.contains("Velocity proxy ready") })
    // But the paper server failed.
    assertTrue(fixture.terminal.writes.any { it.contains("Server failed to start") })
    assertFalse(fixture.terminal.writes.any { it.contains("Watching for changes") })
  }

  // ── Native deploy: jar into plugins/, no LoadRequest ───────────────
  // Blue-green is a "compatible with everything" mode: each backend gets the jar in its plugins/
  // and Paper loads it natively. No companion host, no LoadRequest, no load await — so
  // startServerAndReport can never return LoadFailed for blue-green; that path is covered in
  // HotReloadModeRenderTest.

  @Test
  fun `initial startup deploys the jar into the active backend's plugins natively`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val serverA =
        FakePaperServerManager(File(fixture.ppDir, "server"), fixture.downloader, fixture.ui)
    val serverB =
        FakePaperServerManager(File(fixture.ppDir, "server-swap"), fixture.downloader, fixture.ui)
    val proxy = FakeVelocityManager(File(fixture.ppDir, "proxy"), fixture.ui)
    val mode =
        TestableBlueGreenMode(
            session = fixture.session,
            servers =
                mapOf(BlueGreenMode.Slot.SERVER to serverA, BlueGreenMode.Slot.SWAP to serverB),
            velocityDownloader = FakeVelocityDownloader(File(fixture.ppDir, "cache")),
            velocityManager = proxy,
        )

    assertInstanceOf(DevSession.StartupOutcome.Running::class.java, mode.runStartup())
    assertTrue(
        serverA.calls.contains("copyPluginToPluginsDir(test.jar)"),
        "active backend must receive the jar in plugins/; calls were ${serverA.calls}",
    )
    assertFalse(serverA.calls.any { it.startsWith("stagePlugin") })
    assertTrue(
        serverA.sentLoadRequests.isEmpty(),
        "native modes must not send a LoadRequest",
    )
    assertTrue(
        serverA.calls.contains("copyCompanion(depend=0,softdepend=0)"),
        "native deploy must not rewrite the companion's depends; calls were ${serverA.calls}",
    )
    assertTrue(
        serverA.launchSpecs.isNotEmpty() &&
            serverA.launchSpecs.all { it == fixture.session.launchSpec },
        "the active backend must launch with the session LaunchSpec; got ${serverA.launchSpecs}",
    )
  }

  // ── Rebuild deploys natively to standby and pre-warms the old active ─

  @Test
  fun `rebuild deploys the fresh jar into the standby's plugins and pre-warms the old active`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val serverA =
        FakePaperServerManager(
            File(fixture.ppDir, "server").apply { mkdirs() },
            fixture.downloader,
            fixture.ui,
        )
    val serverB =
        FakePaperServerManager(
            File(fixture.ppDir, "server-swap").apply { mkdirs() },
            fixture.downloader,
            fixture.ui,
        )
    val proxy = FakeVelocityManager(File(fixture.ppDir, "proxy"), fixture.ui)
    val mode =
        TestableBlueGreenMode(
            session = fixture.session,
            servers =
                mapOf(BlueGreenMode.Slot.SERVER to serverA, BlueGreenMode.Slot.SWAP to serverB),
            velocityDownloader = FakeVelocityDownloader(File(fixture.ppDir, "cache")),
            velocityManager = proxy,
        )
    assertInstanceOf(DevSession.StartupOutcome.Running::class.java, mode.runStartup())
    serverA.calls.clear()
    serverB.calls.clear()
    // The pre-warm re-deploys the old active only once it is stopped.
    serverA.runningResult = false
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    val (newSlot, end) = mode.rebuild(fixture.gradle.nextMetadata!!, paperJar)
    mode.awaitPreWarmForTest()

    assertEquals(BlueGreenMode.Slot.SWAP, newSlot)
    assertEquals(PhaseEnd.Watching, end)
    assertTrue(
        serverB.calls.contains("copyPluginToPluginsDir(test.jar)"),
        "the promoted standby must receive the fresh jar in plugins/; calls were ${serverB.calls}",
    )
    assertFalse(serverB.calls.any { it.startsWith("stagePlugin") })
    assertTrue(
        serverA.calls.contains("copyPluginToPluginsDir(test.jar)"),
        "the pre-warmed old active must receive the fresh jar too; calls were ${serverA.calls}",
    )
    // Promoted standby and pre-warmed replacement alike must carry the session LaunchSpec —
    // this is the blue-green half of the mirror-the-args invariant.
    assertTrue(
        serverB.launchSpecs.isNotEmpty() &&
            (serverA.launchSpecs + serverB.launchSpecs).all { it == fixture.session.launchSpec },
        "every blue-green start must use the session LaunchSpec; " +
            "A=${serverA.launchSpecs} B=${serverB.launchSpecs}",
    )
  }

  // ── Fix-recovery restart deploys natively (regression: startFixedServer) ─
  // startFixedServer is the hand-rolled fix-recovery restart. Regression guard: staging instead of
  // deploying boots the recovered server WITHOUT the user's plugin while still reporting "Server
  // ready". It must deploy into plugins/ like every other blue-green path.

  @Test
  fun `startFixedServer deploys the recovered server's jar into plugins natively`() {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val blue = FakePaperServerManager(File(fixture.ppDir, "server"), fixture.downloader, fixture.ui)
    val swap =
        FakePaperServerManager(File(fixture.ppDir, "server-swap"), fixture.downloader, fixture.ui)
    val proxy = FakeVelocityManager(File(fixture.ppDir, "proxy"), fixture.ui)
    val mode =
        TestableBlueGreenMode(
            session = fixture.session,
            servers = mapOf(BlueGreenMode.Slot.SERVER to blue, BlueGreenMode.Slot.SWAP to swap),
            velocityDownloader = FakeVelocityDownloader(File(fixture.ppDir, "cache")),
            velocityManager = proxy,
        )
    val metadata = fixture.gradle.nextMetadata!!
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    val recovered = mode.startFixedServer(metadata, paperJar)

    assertInstanceOf(DevSession.RunningState::class.java, recovered)
    assertTrue(
        blue.calls.contains("copyPluginToPluginsDir(test.jar)"),
        "the recovered server must load the user plugin from plugins/; calls were ${blue.calls}",
    )
    assertFalse(
        blue.calls.any { it.startsWith("stagePlugin") },
        "the recovered server must NOT merely stage the jar — that booted it without the plugin",
    )
    assertTrue(
        blue.launchSpecs.isNotEmpty() &&
            blue.launchSpecs.all { it == fixture.session.launchSpec },
        "the recovered server must launch with the session LaunchSpec; got ${blue.launchSpecs}",
    )
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
    mode.runStartup()

    assertTrue(fixture.terminal.writes.any { it.contains("Starting Paper server") })
    assertTrue(fixture.terminal.writes.any { it.contains("Done (5.5s)") })
  }
}
