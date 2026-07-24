package dev.paperplane.cli.devserver

import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.devserver.instant.InstantBanner
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevSessionTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  private fun createSession(
      jbr: String = "off",
      serverVersion: String? = null,
      mode: DevMode = DevMode.HOT_RELOAD,
  ): DevSession {
    val config =
        PaperPlaneConfig.load(tempDir, ui).let { cfg ->
          cfg.copy(
              dev = cfg.dev.copy(jbr = jbr, mode = mode),
              server = cfg.server.copy(version = serverVersion),
          )
        }
    return DevSession(
        config = config,
        ppDir = tempDir,
        gradle = GradleBridge(tempDir, ui),
        downloader = PaperDownloader(File(tempDir, "cache")),
        projectDir = tempDir,
        ui = ui,
    )
  }

  private fun metadata(paperApiVersion: String = "1.21.4") =
      ProjectMetadata(
          jarPath = "build/libs/test.jar",
          paperApiVersion = paperApiVersion,
          mainClass = "com.example.Main",
          pluginName = "TestPlugin",
          projectDir = tempDir.absolutePath,
          version = "1.0.0",
      )

  // ── formatDuration ──────────────────────────────────────────────────

  @Test
  fun `formatDuration returns milliseconds below threshold`() {
    val session = createSession()
    assertEquals("0ms", session.formatDuration(0))
    assertEquals("1ms", session.formatDuration(1))
    assertEquals("500ms", session.formatDuration(500))
    assertEquals("999ms", session.formatDuration(999))
  }

  @Test
  fun `formatDuration returns seconds at and above threshold`() {
    val session = createSession()
    assertEquals("1.0s", session.formatDuration(1000))
    assertEquals("1.5s", session.formatDuration(1500))
    assertEquals("2.0s", session.formatDuration(2000))
    assertEquals("10.0s", session.formatDuration(10_000))
  }

  @Test
  fun `formatDuration handles fractional seconds`() {
    val session = createSession()
    assertEquals("1.2s", session.formatDuration(1200))
    assertEquals("3.7s", session.formatDuration(3700))
  }

  // ── resolveJava ─────────────────────────────────────────────────────

  @Test
  fun `resolveJava with jbr off returns system java without jbr`() {
    val session = createSession(jbr = "off")
    val result = session.resolveJava()
    assertEquals("java", result.bin)
    assertFalse(result.isJbr)
  }

  @Test
  fun `resolveJava with custom path returns that path`() {
    val customPath = "/opt/custom/bin/java"
    val session = createSession(jbr = customPath)
    val result = session.resolveJava()
    assertEquals(customPath, result.bin)
    assertFalse(result.isJbr)
  }

  // ── formatDurationMs top-level function ────────────────────────────

  @Test
  fun `formatDurationMs matches DevSession formatDuration`() {
    val session = createSession()
    for (ms in listOf(0L, 1L, 500L, 999L, 1000L, 1500L, 5000L, 12345L)) {
      assertEquals(session.formatDuration(ms), dev.paperplane.cli.util.formatDurationMs(ms))
    }
  }

  // ── resolveMcVersion ────────────────────────────────────────────────

  @Test
  fun `resolveMcVersion returns metadata version when no config override`() {
    val session = createSession()
    assertEquals("1.21.4", session.resolveMcVersion(metadata("1.21.4")))
  }

  @Test
  fun `resolveMcVersion returns config version when set`() {
    val session = createSession(serverVersion = "1.20.6")
    assertEquals("1.20.6", session.resolveMcVersion(metadata("1.21.4")))
  }

  @Test
  fun `resolveMcVersion accepts all supported api versions`() {
    val session = createSession()
    for (api in Versions.SUPPORTED_API_VERSIONS) {
      // Should not throw for any supported version
      session.resolveMcVersion(metadata(api))
    }
  }

  @Test
  fun `resolveMcVersion throws for unsupported version`() {
    val session = createSession()
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.resolveMcVersion(metadata("1.17.1"))
        }
    assertTrue(ex.message!!.contains("not supported"))
    assertTrue(ex.message!!.contains("1.17"))
  }

  // ── enforceHotReloadEligibility ─────────────────────────────────────

  @Test
  fun `hot-reload rejects Paper below 1_19_3`() {
    val session = createSession(mode = DevMode.HOT_RELOAD)
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.enforceHotReloadEligibility(metadata("1.19.2"))
        }
    assertTrue(ex.message!!.contains("Hot-reload requires Paper 1.19.3+"))
    assertTrue(ex.message!!.contains("restart") && ex.message!!.contains("blue-green"))
  }

  @Test
  fun `hot-reload accepts Paper at and above 1_19_3`() {
    val session = createSession(mode = DevMode.HOT_RELOAD)
    // Exactly the floor and above must not throw.
    session.enforceHotReloadEligibility(metadata("1.19.3"))
    session.enforceHotReloadEligibility(metadata("1.21.11"))
  }

  @Test
  fun `hot-reload rejects a curated can't-late-load dependency`() {
    val session = createSession(mode = DevMode.HOT_RELOAD)
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.enforceHotReloadEligibility(metadata().copy(depend = listOf("CommandAPI")))
        }
    assertTrue(ex.message!!.contains("plugin.yml depend 'CommandAPI'"))
    assertTrue(ex.message!!.contains("dev.fallback"))
  }

  @Test
  fun `config server version overrides metadata for the eligibility floor`() {
    val session = createSession(mode = DevMode.HOT_RELOAD, serverVersion = "1.19.2")
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.enforceHotReloadEligibility(metadata("1.21.4"))
        }
    assertTrue(ex.message!!.contains("Hot-reload requires Paper 1.19.3+"))
  }

  @Test
  fun `initialBuild enforces hot-reload eligibility`() {
    // Wiring test: the check must actually run on the startup path, not just exist as a function.
    val fixture = DevSessionFixture(tempDir).withMetadata(paperApiVersion = "1.19.2")
    val server =
        FakePaperServerManager(
            fixture.ppDir,
            fixture.downloader,
            fixture.ui,
        )
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          fixture.session.initialBuild(fixture.gradle.nextMetadata!!, server)
        }
    assertTrue(ex.message!!.contains("Hot-reload requires Paper 1.19.3+"))
  }

  @Test
  fun `handleFixAttempt surfaces an eligibility violation and keeps the fix loop alive`() {
    // The fix loop runs on the watcher thread — an eligibility violation must render as a failed
    // attempt, not an escaping throw that would kill the watcher and hang the session. This is also
    // the enforcement point for a session whose broken initial build hid its dependencies from
    // session-start selection.
    val fixture = DevSessionFixture(tempDir).withMetadata(paperApiVersion = "1.19.2")

    val result = fixture.session.handleFixAttempt(null)

    assertEquals(DevSession.FixAttempt.BuildFailed, result)
    assertTrue(
        fixture.terminal.writes.any { it.contains("Hot-reload requires Paper 1.19.3+") },
        "the floor's guidance must reach the user; writes were ${fixture.terminal.writes}",
    )
  }

  @Test
  fun `restart and blue-green skip hot-reload eligibility`() {
    for (mode in
        listOf(
            DevMode.RESTART,
            DevMode.BLUE_GREEN,
        )) {
      val session = createSession(mode = mode)
      // Even ancient Paper plus a curated dependency is fine for native modes — no rules apply.
      session.enforceHotReloadEligibility(
          metadata("1.16.5").copy(depend = listOf("ProtocolLib"))
      )
    }
  }

  // ── preflightMetadata / demoteMode ──────────────────────────────────

  @Test
  fun `preflight result is consumed by exactly one resolveMetadata call`() {
    val fixture = DevSessionFixture(tempDir)

    val preflight = fixture.session.preflightMetadata()
    assertEquals(listOf("metadata"), fixture.gradle.calls.filter { it == "metadata" })

    // The dispatched mode's own call reuses the preflight without touching Gradle...
    assertEquals(preflight, fixture.session.resolveMetadata())
    assertEquals(1, fixture.gradle.calls.count { it == "metadata" })

    // ...and any later call (fix recovery, restart paths) sees fresh Gradle state again.
    fixture.session.resolveMetadata()
    assertEquals(2, fixture.gradle.calls.count { it == "metadata" })
  }

  @Test
  fun `demoteMode swaps the config mode and records the selection report`() {
    val fixture =
        DevSessionFixture(
            tempDir,
            config = PaperPlaneConfig(dev = DevConfig(mode = DevMode.HOT_RELOAD)),
        )
    val rejection = ModeRejection("commandapi", "plugin.yml depend 'CommandAPI'", "reason")

    fixture.session.demoteMode(DevMode.RESTART, listOf(rejection))

    assertEquals(DevMode.RESTART, fixture.session.config.dev.mode)
    val report = fixture.session.selectionReport!!
    assertEquals(DevMode.HOT_RELOAD, report.requested)
    assertEquals(DevMode.RESTART, report.actual)
    assertEquals(listOf(rejection), report.rejections)
  }

  @Test
  fun `showServerInfo reports a demotion next to the mode line`() {
    val fixture = DevSessionFixture(tempDir)
    fixture.session.demoteMode(
        DevMode.RESTART,
        listOf(ModeRejection("commandapi", "plugin.yml depend 'CommandAPI'", "reason")),
    )

    fixture.ui.block {
      fixture.session.showServerInfo(metadata(), "localhost:25565", "restart")
    }

    val writes = fixture.terminal.writes
    assertTrue(writes.any { it.contains("Mode:") && it.contains("restart") })
    assertTrue(
        writes.any {
          it.contains("Demoted from hot-reload") && it.contains("plugin.yml depend 'CommandAPI'")
        },
        "tier report must state the demotion and its cause; writes were $writes",
    )
  }

  @Test
  fun `showServerInfo stays silent about demotion for a session running its requested mode`() {
    val fixture = DevSessionFixture(tempDir)
    fixture.ui.block {
      fixture.session.showServerInfo(metadata(), "localhost:25565", "hot-reload")
    }
    assertFalse(fixture.terminal.writes.any { it.contains("Demoted") })
  }

  // ── syncOpsBackToConfig ─────────────────────────────────────────────

  private fun opsBackSetup(
      initialOps: List<String> = emptyList(),
      opBanlist: List<String> = emptyList(),
      liveOpNames: List<String>,
  ): Pair<DevSession, PaperServerManager> {
    // Seed paperplane.yml so PaperPlaneConfig.load picks up ops/opBanlist.
    val yaml = buildString {
      append("server:\n")
      if (initialOps.isNotEmpty()) {
        append("  ops:\n")
        initialOps.forEach { append("    - \"$it\"\n") }
      }
      if (opBanlist.isNotEmpty()) {
        append("  op-banlist:\n")
        opBanlist.forEach { append("    - \"$it\"\n") }
      }
    }
    File(tempDir, "paperplane.yml").writeText(yaml)
    val config = PaperPlaneConfig.load(tempDir, ui)
    val session =
        DevSession(
            config = config,
            ppDir = tempDir,
            gradle = GradleBridge(tempDir, ui),
            downloader = PaperDownloader(File(tempDir, "cache")),
            projectDir = tempDir,
            ui = ui,
        )
    val serverDir = File(tempDir, "server").apply { mkdirs() }
    val manager = PaperServerManager(serverDir, PaperDownloader(File(tempDir, "cache")), ui)
    // Seed ops.json in the format PaperServerManager writes — readOpNames parses it.
    val entries = liveOpNames.joinToString(",") { "{\"name\":\"$it\",\"level\":4}" }
    File(serverDir, "ops.json").writeText("[$entries]")
    return session to manager
  }

  @Test
  fun `syncOpsBackToConfig appends new auto-opped names preserving order`() {
    val (session, manager) =
        opsBackSetup(
            initialOps = listOf("alice", "bob"),
            liveOpNames = listOf("alice", "charlie", "bob", "dave"),
        )
    session.syncOpsBackToConfig(manager)

    assertEquals(listOf("alice", "bob", "charlie", "dave"), session.config.server.ops)
    // Persisted to paperplane.yml too
    val reloaded = PaperPlaneConfig.load(tempDir, ui)
    assertEquals(listOf("alice", "bob", "charlie", "dave"), reloaded.server.ops)
  }

  @Test
  fun `syncOpsBackToConfig excludes banlisted names`() {
    val (session, manager) =
        opsBackSetup(
            initialOps = listOf("alice"),
            opBanlist = listOf("eve"),
            liveOpNames = listOf("alice", "eve", "bob"),
        )
    session.syncOpsBackToConfig(manager)

    assertEquals(listOf("alice", "bob"), session.config.server.ops)
  }

  @Test
  fun `syncOpsBackToConfig is a no-op when no new names appeared`() {
    val (session, manager) =
        opsBackSetup(
            initialOps = listOf("alice", "bob"),
            liveOpNames = listOf("alice", "bob"),
        )
    // Delete paperplane.yml after setup so we can detect a write.
    File(tempDir, "paperplane.yml").delete()
    session.syncOpsBackToConfig(manager)

    assertFalse(
        File(tempDir, "paperplane.yml").exists(),
        "no-op sync must not rewrite paperplane.yml",
    )
    assertEquals(listOf("alice", "bob"), session.config.server.ops)
  }

  @Test
  fun `syncOpsBackToConfig is a no-op when ops json is missing`() {
    val (session, manager) = opsBackSetup(initialOps = listOf("alice"), liveOpNames = emptyList())
    File(manager.serverDir, "ops.json").delete()
    File(tempDir, "paperplane.yml").delete()
    session.syncOpsBackToConfig(manager)

    assertFalse(File(tempDir, "paperplane.yml").exists())
  }

  @Test
  fun `resolveMcVersion throws for unsupported config override`() {
    val session = createSession(serverVersion = "1.16.5")
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.resolveMcVersion(metadata("1.21.4"))
        }
    assertTrue(ex.message!!.contains("1.16"))
  }

  // ── maybeInvalidateGradleConnection ─────────────────────────────────

  private class CountingGradleBridge(projectDir: File, ui: TerminalUI) :
      GradleBridge(projectDir, ui) {
    var closeCount = 0
      private set

    override fun doClose() {
      closeCount++
    }
  }

  private fun invalidationSession(bridge: CountingGradleBridge): DevSession {
    val config = PaperPlaneConfig.load(tempDir, ui)
    return DevSession(
        config = config,
        ppDir = tempDir,
        gradle = bridge,
        downloader = PaperDownloader(File(tempDir, "cache")),
        projectDir = tempDir,
        ui = ui,
    )
  }

  @Test
  fun `maybeInvalidateGradleConnection does nothing for source-only changes`() {
    val bridge = CountingGradleBridge(tempDir, ui)
    val session = invalidationSession(bridge)
    val srcPath = FileWatcher.normalizePath(File(tempDir, "src/main/java/Foo.java").absolutePath)

    session.maybeInvalidateGradleConnection(listOf(srcPath))

    assertEquals(0, bridge.closeCount)
  }

  @Test
  fun `maybeInvalidateGradleConnection closes when build-gradle-kts changes`() {
    val bridge = CountingGradleBridge(tempDir, ui)
    val session = invalidationSession(bridge)
    val buildPath = FileWatcher.normalizePath(File(tempDir, "build.gradle.kts").absolutePath)

    session.maybeInvalidateGradleConnection(listOf(buildPath))

    assertEquals(1, bridge.closeCount)
  }

  @Test
  fun `maybeInvalidateGradleConnection closes when settings or properties change`() {
    val bridge = CountingGradleBridge(tempDir, ui)
    val session = invalidationSession(bridge)
    val settingsPath = FileWatcher.normalizePath(File(tempDir, "settings.gradle.kts").absolutePath)
    val propsPath = FileWatcher.normalizePath(File(tempDir, "gradle.properties").absolutePath)

    session.maybeInvalidateGradleConnection(listOf(settingsPath))
    session.maybeInvalidateGradleConnection(listOf(propsPath))

    assertEquals(2, bridge.closeCount)
  }

  @Test
  fun `maybeInvalidateGradleConnection closes once for mixed change sets`() {
    val bridge = CountingGradleBridge(tempDir, ui)
    val session = invalidationSession(bridge)
    val srcPath = FileWatcher.normalizePath(File(tempDir, "src/main/java/Foo.java").absolutePath)
    val buildPath = FileWatcher.normalizePath(File(tempDir, "build.gradle.kts").absolutePath)

    session.maybeInvalidateGradleConnection(listOf(srcPath, buildPath))

    assertEquals(1, bridge.closeCount)
  }

  @Test
  fun `maybeInvalidateGradleConnection matches normalized watcher paths byte-for-byte`() {
    // The watcher emits paths via FileWatcher.normalizePath, and the check normalizes the
    // build-config paths the same way before comparison. Whatever a real file's absolute path
    // looks like on disk, sending it back through normalizePath must produce a hit — otherwise
    // the watcher and the check would silently disagree.
    val bridge = CountingGradleBridge(tempDir, ui)
    val session = invalidationSession(bridge)
    val watcherEmitted =
        FileWatcher.normalizePath(File(tempDir, "build.gradle.kts").absoluteFile.path)

    session.maybeInvalidateGradleConnection(listOf(watcherEmitted))

    assertEquals(1, bridge.closeCount)
  }

  @Test
  fun `maybeInvalidateGradleConnection ignores non-matching paths in the changed list`() {
    val bridge = CountingGradleBridge(tempDir, ui)
    val session = invalidationSession(bridge)
    val unrelated = FileWatcher.normalizePath(File(tempDir, "build.gradle.unrelated").absolutePath)

    session.maybeInvalidateGradleConnection(listOf(unrelated))

    assertEquals(0, bridge.closeCount)
  }

  // ── Server-info banner ──────────────────────────────────────────────

  private fun bannerLines(instant: InstantBanner): List<String> {
    val fixture = DevSessionFixture(tempDir).withMetadata(pluginName = "Foo", version = "2.3.4")
    fixture.session.showServerInfo(
        checkNotNull(fixture.gradle.nextMetadata),
        "localhost:25565",
        "restart",
        instant,
    )
    return fixture.terminal.writes
  }

  @Test
  fun `an armed instant lane rides the mode line rather than taking one of its own`() {
    val lines = bannerLines(InstantBanner.Armed)

    assertTrue(lines.any { it.contains("Mode:") && it.contains("restart + instant") }, "$lines")
    assertFalse(lines.any { it.contains("Instant:") }, "the tier gets no line of its own: $lines")
  }

  @Test
  fun `a switched-off instant lane says nothing at all`() {
    val lines = bannerLines(InstantBanner.Disabled)

    assertTrue(lines.any { it.contains("Mode:") && it.contains("restart") }, "$lines")
    assertFalse(lines.any { it.contains("instant", ignoreCase = true) }, "$lines")
  }

  @Test
  fun `a lane that could not arm warns, naming the reason and the consequence`() {
    // The one instant state the user did not choose. It should be unreachable in a working
    // install, which is exactly why it must not degrade into a missing suffix nobody notices.
    val lines = bannerLines(InstantBanner.Unavailable("no agent in the server JVM"))

    assertFalse(
        lines.any { it.contains("restart + instant") },
        "an unavailable lane must not claim the mode suffix: $lines",
    )
    val warning = lines.single { it.contains("Instant unavailable") }
    assertTrue(
        warning.contains("\u26a0"),
        "the line must read as a warning, not an info row: $warning",
    )
    assertTrue(warning.contains("no agent in the server JVM"), warning)
    assertTrue(warning.contains("full reload"), "say what it costs the user: $warning")
  }
}
