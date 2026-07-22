package dev.paperplane.cli.server

import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperServerManagerTest {

  @TempDir lateinit var tempDir: File
  private val terminal = RecordingTerminal()
  private val ui = TerminalUI(terminal)

  private fun createManager(port: Int = 25566): PaperServerManager {
    val serverDir = File(tempDir, "server-$port")
    val cacheDir = File(tempDir, "cache")
    val downloader = PaperDownloader(cacheDir)
    return PaperServerManager(serverDir, downloader, ui, port = port)
  }

  @Test
  fun `configure creates server files with correct port`() {
    val manager = createManager(25566)
    manager.configure()

    val props = File(manager.serverDir, "server.properties").readText()
    assertTrue(props.contains("server-port=25566"))
    assertTrue(props.contains("online-mode=false"))
    assertTrue(props.contains("accepts-transfers=true"))
    assertTrue(File(manager.serverDir, "bukkit.yml").exists())
    assertTrue(File(manager.serverDir, "spigot.yml").exists())
  }

  @Test
  fun `configure overwrites existing server properties`() {
    val manager = createManager(25566)
    manager.serverDir.mkdirs()
    // Simulate Paper having rewritten server.properties without accepts-transfers
    File(manager.serverDir, "server.properties").writeText("server-port=25566\nonline-mode=false\n")

    manager.configure()

    val props = File(manager.serverDir, "server.properties").readText()
    assertTrue(props.contains("accepts-transfers=true"), "should overwrite with new properties")
  }

  @Test
  fun `configure uses different port for each server`() {
    val blue = createManager(25566)
    val green = createManager(25567)
    blue.configure()
    green.configure()

    val blueProps = File(blue.serverDir, "server.properties").readText()
    val greenProps = File(green.serverDir, "server.properties").readText()
    assertTrue(blueProps.contains("server-port=25566"))
    assertTrue(greenProps.contains("server-port=25567"))
  }

  @Test
  fun `configure merges user properties into server properties`() {
    val manager = createManager(25566)
    manager.configure(
        ServerConfig(
            properties =
                mapOf(
                    "view-distance" to "10",
                    "gamemode" to "creative",
                    "level-seed" to "12345",
                    "resource-pack" to "https://example.com/pack.zip",
                )
        )
    )

    val props = File(manager.serverDir, "server.properties").readText()
    assertTrue(props.contains("view-distance=10"), "user override should win")
    assertFalse(props.contains("view-distance=4"), "default should be replaced")
    assertTrue(props.contains("gamemode=creative"), "new key should be added")
    assertTrue(props.contains("level-seed=12345"))
    assertTrue(props.contains("resource-pack=https://example.com/pack.zip"))
    // Defaults for unrelated keys still present
    assertTrue(props.contains("simulation-distance=4"))
  }

  @Test
  fun `configure ignores user attempts to override managed keys`() {
    val manager = createManager(25566)
    manager.configure(
        ServerConfig(
            properties =
                mapOf(
                    "server-port" to "9999",
                    "online-mode" to "true",
                    "accepts-transfers" to "false",
                )
        )
    )

    val props = File(manager.serverDir, "server.properties").readText()
    assertTrue(props.contains("server-port=25566"), "managed port cannot be overridden")
    assertFalse(props.contains("server-port=9999"))
    assertTrue(props.contains("online-mode=false"), "managed online-mode cannot be overridden")
    assertFalse(props.contains("online-mode=true"))
    assertTrue(
        props.contains("accepts-transfers=true"),
        "managed accepts-transfers cannot be overridden",
    )
  }

  @Test
  fun `configure writes ops json when ops list is non-empty`() {
    val manager = createManager()
    manager.configure(ServerConfig(ops = listOf("alice", "bob")))

    val opsFile = File(manager.serverDir, "ops.json")
    assertTrue(opsFile.exists())
    val content = opsFile.readText()
    assertTrue(content.contains("\"name\":\"alice\""))
    assertTrue(content.contains("\"name\":\"bob\""))
    assertTrue(content.contains("\"level\":4"))
    // Offline-mode UUID for "alice" — deterministic hash of "OfflinePlayer:alice"
    val expectedAliceUuid =
        java.util.UUID.nameUUIDFromBytes("OfflinePlayer:alice".toByteArray()).toString()
    assertTrue(content.contains(expectedAliceUuid))
  }

  @Test
  fun `configure writes no ops json when ops list is empty`() {
    val manager = createManager()
    manager.configure(ServerConfig(ops = emptyList()))

    assertFalse(File(manager.serverDir, "ops.json").exists())
  }

  @Test
  fun `readOpNames returns names from ops json`() {
    val manager = createManager()
    manager.configure(ServerConfig(ops = listOf("alice", "bob")))

    val names = manager.readOpNames()
    assertEquals(listOf("alice", "bob"), names)
  }

  @Test
  fun `readOpNames returns empty list when ops json is missing`() {
    val manager = createManager()
    manager.configure()
    assertTrue(manager.readOpNames().isEmpty())
  }

  @Test
  fun `readOpNames returns empty list when ops json is malformed`() {
    val manager = createManager()
    manager.configure()
    File(manager.serverDir, "ops.json").writeText("not valid json")
    assertTrue(manager.readOpNames().isEmpty())
  }

  @Test
  fun `configure writes op-banlist json when non-empty`() {
    val manager = createManager()
    manager.configure(ServerConfig(opBanlist = listOf("alice", "bob")))

    val banlistFile = File(manager.serverDir, ".paperplane/op-banlist.json")
    assertTrue(banlistFile.exists())
    val content = banlistFile.readText()
    assertTrue(content.contains("alice"))
    assertTrue(content.contains("bob"))
  }

  @Test
  fun `configure deletes op-banlist json when empty`() {
    val manager = createManager()
    // First write a banlist...
    manager.configure(ServerConfig(opBanlist = listOf("alice")))
    assertTrue(File(manager.serverDir, ".paperplane/op-banlist.json").exists())
    // ...then clear it.
    manager.configure(ServerConfig(opBanlist = emptyList()))
    assertFalse(File(manager.serverDir, ".paperplane/op-banlist.json").exists())
  }

  @Test
  fun `configure filters banlisted names out of ops json`() {
    val manager = createManager()
    manager.configure(
        ServerConfig(ops = listOf("alice", "bob", "charlie"), opBanlist = listOf("bob"))
    )

    val opsContent = File(manager.serverDir, "ops.json").readText()
    assertTrue(opsContent.contains("alice"))
    assertFalse(opsContent.contains("\"name\":\"bob\""))
    assertTrue(opsContent.contains("charlie"))
  }

  @Test
  fun `configure deletes stale ops json when ops list becomes empty`() {
    val manager = createManager()
    manager.configure(ServerConfig(ops = listOf("alice")))
    assertTrue(File(manager.serverDir, "ops.json").exists())
    manager.configure(ServerConfig(ops = emptyList()))
    assertFalse(
        File(manager.serverDir, "ops.json").exists(),
        "stale ops.json from a previous run should be cleared",
    )
  }

  @Test
  fun `configureVelocityForwarding writes velocity secret`() {
    val manager = createManager()
    manager.configure()
    manager.configureVelocityForwarding("test-secret-123")

    val paperConfig = File(manager.serverDir, "config/paper-global.yml").readText()
    assertTrue(paperConfig.contains("secret") && paperConfig.contains("test-secret-123"))
  }

  @Test
  fun `configure deep-merges user paperGlobal into paper-global yml`() {
    val manager = createManager()
    val userOverride =
        yamlMap(
            """
            chunks:
              delay-chunk-unloads-by: "0s"
            anticheat:
              anti-xray:
                enabled: false
            """
                .trimIndent() + "\n"
        )
    manager.configure(dev.paperplane.cli.config.ServerConfig(paperGlobal = userOverride))

    val content = File(manager.serverDir, "config/paper-global.yml").readText()
    // User overrides present
    assertTrue(content.contains("delay-chunk-unloads-by"))
    assertTrue(content.contains("anticheat"))
    // PaperPlane default preserved
    assertTrue(content.contains("timings"))
  }

  @Test
  fun `configure deep-merges user paperWorldDefaults`() {
    val manager = createManager()
    val userOverride =
        yamlMap(
            """
            entity-tracking-range:
              players: "96"
            """
                .trimIndent() + "\n"
        )
    manager.configure(dev.paperplane.cli.config.ServerConfig(paperWorldDefaults = userOverride))

    val content = File(manager.serverDir, "config/paper-world-defaults.yml").readText()
    assertTrue(content.contains("entity-tracking-range"))
    // Default keys preserved
    assertTrue(content.contains("auto-save-interval"))
    assertTrue(content.contains("keep-spawn-loaded"))
  }

  @Test
  fun `configureVelocityForwarding preserves user paperGlobal overrides`() {
    val manager = createManager()
    val userOverride =
        yamlMap(
            """
            chunks:
              delay-chunk-unloads-by: "0s"
            """
                .trimIndent() + "\n"
        )
    manager.configure(dev.paperplane.cli.config.ServerConfig(paperGlobal = userOverride))
    manager.configureVelocityForwarding("secret-xyz")

    val content = File(manager.serverDir, "config/paper-global.yml").readText()
    // Velocity settings present (managed — must win)
    assertTrue(content.contains("secret") && content.contains("secret-xyz"))
    // User override still present (wasn't clobbered)
    assertTrue(content.contains("delay-chunk-unloads-by"))
    // PaperPlane default still present
    assertTrue(content.contains("timings"))
  }

  @Test
  fun `configureVelocityForwarding secret wins over user paperGlobal override`() {
    val manager = createManager()
    val userOverride =
        yamlMap(
            """
            proxies:
              velocity:
                secret: "user-attempted-secret"
            """
                .trimIndent() + "\n"
        )
    manager.configure(dev.paperplane.cli.config.ServerConfig(paperGlobal = userOverride))
    manager.configureVelocityForwarding("managed-secret-xyz")

    val content = File(manager.serverDir, "config/paper-global.yml").readText()
    assertTrue(content.contains("managed-secret-xyz"), "managed secret must win")
    assertFalse(
        content.contains("user-attempted-secret"),
        "user-supplied velocity secret must be clobbered",
    )
  }

  @Test
  fun `hasExitedUnexpectedly is false before any start`() {
    assertFalse(createManager().hasExitedUnexpectedly())
  }

  @Test
  fun `hasExitedUnexpectedly tracks crash then requested stop then restart`() {
    val manager = createManager()
    File(tempDir, "server-25566").mkdirs()
    val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val missingJar = File(tempDir, "missing.jar")

    // The JVM starts and exits immediately (no such jar) — a death the manager did not request.
    manager.start(
        missingJar,
        LaunchSpec(javaBin, isJbr = false, jvmArgs = emptyList()),
        attachAgent = false,
    )
    waitUntil("process self-terminates") { !manager.isRunning() }
    assertTrue(
        manager.hasExitedUnexpectedly(),
        "a self-terminated process is an unexpected exit",
    )

    // Requesting a stop — even on an already-dead process, as cleanup paths do — settles it.
    manager.stop()
    assertFalse(
        manager.hasExitedUnexpectedly(),
        "after stop() is requested the exit is no longer unexpected",
    )

    // A fresh start must reset the requested-stop flag so the next crash is caught again.
    manager.start(
        missingJar,
        LaunchSpec(javaBin, isJbr = false, jvmArgs = emptyList()),
        attachAgent = false,
    )
    waitUntil("restarted process self-terminates") { !manager.isRunning() }
    assertTrue(
        manager.hasExitedUnexpectedly(),
        "start() must re-arm unexpected-exit detection",
    )
  }

  private fun waitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 30_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting: $what")
      Thread.sleep(50)
    }
  }

  @Test
  fun `isRunning returns false when not started`() {
    val manager = createManager()
    assertFalse(manager.isRunning())
  }

  @Test
  fun `stop is safe when not started`() {
    val manager = createManager()
    // Should not throw
    manager.stop()
  }

  @Test
  fun `sendCommand is safe when not started`() {
    val manager = createManager()
    // Should not throw
    manager.sendCommand("test")
  }

  // ── stagePlugin tests (hot-reload deploy) ───────────────────────────

  @Test
  fun `stagePlugin stages to dot-paperplane staged not plugins`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("jar-content-v1")

    val stagedPath = manager.stagePlugin(sourceJar)

    val stagedFile = File(stagedPath)
    assertTrue(stagedFile.isAbsolute, "Staged path should be absolute")
    assertTrue(stagedFile.exists(), "Staged file should exist at $stagedPath")
    assertEquals("jar-content-v1", stagedFile.readText())
    assertTrue(
        stagedPath.replace(File.separatorChar, '/').contains(".paperplane/staged/"),
        "Plugin must be staged outside plugins/ — was: $stagedPath",
    )
    assertFalse(
        File(manager.serverDir, "plugins/myplugin.jar").exists(),
        "Plugin must NOT land in plugins/ (Paper would auto-load it).",
    )
  }

  @Test
  fun `stagePlugin overwrites existing staged file`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("old-content")
    manager.stagePlugin(sourceJar)
    sourceJar.writeText("new-content")
    val stagedPath = manager.stagePlugin(sourceJar)
    assertEquals("new-content", File(stagedPath).readText())
  }

  // ── copyPluginToPluginsDir tests (restart / blue-green native deploy) ─

  @Test
  fun `copyPluginToPluginsDir lands the jar in plugins so Paper loads it natively`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("jar-content-v1")

    manager.copyPluginToPluginsDir(sourceJar)

    val deployed = File(manager.serverDir, "plugins/myplugin.jar")
    assertTrue(deployed.exists(), "Plugin must land in plugins/ for native loading")
    assertEquals("jar-content-v1", deployed.readText())
    assertFalse(
        File(manager.serverDir, ".paperplane/staged/myplugin.jar").exists(),
        "Native deploy must not also stage the jar",
    )
  }

  @Test
  fun `copyPluginToPluginsDir overwrites an existing plugin jar`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("old-content")
    manager.copyPluginToPluginsDir(sourceJar)
    sourceJar.writeText("new-content")

    manager.copyPluginToPluginsDir(sourceJar)

    assertEquals("new-content", File(manager.serverDir, "plugins/myplugin.jar").readText())
  }

  @Test
  fun `copyPluginToPluginsDir leaves no temp file`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("jar-content")

    manager.copyPluginToPluginsDir(sourceJar)

    val pluginsDir = File(manager.serverDir, "plugins")
    val tempFiles = pluginsDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
    assertTrue(
        tempFiles.isEmpty(),
        "No .tmp files should remain, found: ${tempFiles.map { it.name }}",
    )
  }

  @Test
  fun `copyPluginToPluginsDir removes the previously deployed jar when the name changes`() {
    val manager = createManager()
    manager.configure()
    val v1 = File(tempDir, "myplugin-1.0.0.jar").apply { writeText("v1") }
    val v2 = File(tempDir, "myplugin-1.1.0.jar").apply { writeText("v2") }
    manager.copyPluginToPluginsDir(v1)

    manager.copyPluginToPluginsDir(v2)

    assertFalse(
        File(manager.serverDir, "plugins/myplugin-1.0.0.jar").exists(),
        "the old-name jar must be removed or Paper rejects the new one as a duplicate plugin",
    )
    assertTrue(File(manager.serverDir, "plugins/myplugin-1.1.0.jar").exists())
  }

  @Test
  fun `removeDeployedPlugin clears a natively deployed jar and its record`() {
    val manager = createManager()
    manager.configure()
    val jar = File(tempDir, "myplugin.jar").apply { writeText("v1") }
    manager.copyPluginToPluginsDir(jar)

    manager.removeDeployedPlugin(jar.name)

    assertFalse(File(manager.serverDir, "plugins/myplugin.jar").exists())
    assertFalse(File(manager.serverDir, ".paperplane/deployed-plugin").exists())
  }

  @Test
  fun `removeDeployedPlugin removes an unrecorded jar by its current name`() {
    val manager = createManager()
    manager.configure()
    File(manager.serverDir, "plugins").mkdirs()
    File(manager.serverDir, "plugins/myplugin.jar").writeText("stale native deploy, no record")

    manager.removeDeployedPlugin("myplugin.jar")

    assertFalse(File(manager.serverDir, "plugins/myplugin.jar").exists())
  }

  @Test
  fun `copyCompanion writes companion jar with depends inherited into plugin yml`() {
    val manager = createManager()
    manager.configure()

    manager.copyCompanion(
        depend = listOf("WorldGuard", "Vault"),
        softdepend = listOf("PlaceholderAPI"),
    )

    val companionJar = File(manager.serverDir, "plugins/paperplane-companion.jar")
    assertTrue(companionJar.exists(), "companion jar must land in plugins/")

    val yml =
        java.util.jar.JarFile(companionJar).use { jar ->
          val entry =
              jar.getJarEntry("plugin.yml") ?: error("plugin.yml missing inside companion jar")
          jar.getInputStream(entry).bufferedReader().readText()
        }
    assertTrue(
        yml.contains("depend: [WorldGuard, Vault]"),
        "companion plugin.yml must inherit user's depend; got: $yml",
    )
    assertTrue(
        yml.contains("softdepend: [PlaceholderAPI]"),
        "companion plugin.yml must inherit user's softdepend; got: $yml",
    )
    assertTrue(yml.contains("name: PaperPlane"), "original companion fields must be preserved")
  }

  @Test
  fun `copyCompanion with no depends leaves plugin yml without depend lines`() {
    val manager = createManager()
    manager.configure()

    manager.copyCompanion(depend = emptyList(), softdepend = emptyList())

    val companionJar = File(manager.serverDir, "plugins/paperplane-companion.jar")
    val yml =
        java.util.jar.JarFile(companionJar).use { jar ->
          jar.getInputStream(jar.getJarEntry("plugin.yml")).bufferedReader().readText()
        }
    assertFalse(yml.contains("depend:"), "no depends → no depend line; got: $yml")
  }

  @Test
  fun `stagePlugin leaves no temp file`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("jar-content")

    manager.stagePlugin(sourceJar)

    val stageDir = File(manager.serverDir, ".paperplane/staged")
    val tempFiles = stageDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
    assertTrue(
        tempFiles.isEmpty(),
        "No .tmp files should remain, found: ${tempFiles.map { it.name }}",
    )
  }
}
