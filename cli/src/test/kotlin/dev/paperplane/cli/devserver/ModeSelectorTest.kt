package dev.paperplane.cli.devserver

import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.plugins.PluginDependency
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModeSelectorTest {

  @TempDir lateinit var tempDir: File

  private val selector = ModeSelector()

  private fun metadata(
      depend: List<String> = emptyList(),
      softdepend: List<String> = emptyList(),
      runtimeClasspath: List<String> = emptyList(),
      paperApiVersion: String = "1.21.4",
  ) =
      ProjectMetadata(
          jarPath = "build/libs/test.jar",
          paperApiVersion = paperApiVersion,
          mainClass = "com.example.Main",
          pluginName = "TestPlugin",
          projectDir = "/fake/project",
          version = "1.0.0",
          depend = depend,
          softdepend = softdepend,
          runtimeClasspath = runtimeClasspath,
      )

  private fun config(
      plugins: List<PluginDependency> = emptyList(),
      serverVersion: String? = null,
  ) = PaperPlaneConfig(server = ServerConfig(version = serverVersion, plugins = plugins))

  private fun rejections(
      mode: DevMode = DevMode.HOT_RELOAD,
      config: PaperPlaneConfig = config(),
      metadata: ProjectMetadata? = metadata(),
      pluginsDir: File? = null,
  ) = selector.rejections(mode, config, metadata, pluginsDir)

  // ── mode gating ─────────────────────────────────────────────────────

  @Test
  fun `native modes are never rejected, even with a curated dependency present`() {
    val meta = metadata(depend = listOf("CommandAPI", "ProtocolLib"))
    for (mode in listOf(DevMode.RESTART, DevMode.BLUE_GREEN)) {
      assertEquals(emptyList<ModeRejection>(), rejections(mode = mode, metadata = meta))
    }
  }

  @Test
  fun `hot-reload with no curated dependencies and a modern Paper passes`() {
    assertEquals(emptyList<ModeRejection>(), rejections())
  }

  // ── plugin.yml depend / softdepend ──────────────────────────────────

  @Test
  fun `depend hit rejects hot-reload, case-insensitively`() {
    val result = rejections(metadata = metadata(depend = listOf("commandapi")))
    assertEquals(1, result.size)
    assertEquals("commandapi", result[0].ruleId)
    assertEquals("plugin.yml depend 'commandapi'", result[0].matchedBy)
    assertTrue(result[0].reason.contains("CommandAPI"))
  }

  @Test
  fun `softdepend hit rejects hot-reload`() {
    val result = rejections(metadata = metadata(softdepend = listOf("ProtocolLib")))
    assertEquals(1, result.size)
    assertEquals("protocollib", result[0].ruleId)
    assertEquals("plugin.yml softdepend 'ProtocolLib'", result[0].matchedBy)
  }

  @Test
  fun `unrelated depends do not fire`() {
    val result = rejections(metadata = metadata(depend = listOf("Vault", "PlaceholderAPI")))
    assertEquals(emptyList<ModeRejection>(), result)
  }

  // ── runtime classpath ───────────────────────────────────────────────

  @Test
  fun `shaded CommandAPI on the runtime classpath rejects hot-reload`() {
    val gradleCachePath =
        "/Users/dev/.gradle/caches/modules-2/files-2.1/dev.jorel/commandapi-bukkit-shade/" +
            "10.1.2/abc123def/commandapi-bukkit-shade-10.1.2.jar"
    val result = rejections(metadata = metadata(runtimeClasspath = listOf(gradleCachePath)))
    assertEquals(1, result.size)
    assertEquals("commandapi", result[0].ruleId)
    assertEquals("runtime classpath 'commandapi-bukkit-shade-10.1.2'", result[0].matchedBy)
  }

  @Test
  fun `artifact matching is anchored — a plugin merely mentioning commandapi mid-name passes`() {
    val result =
        rejections(
            metadata =
                metadata(runtimeClasspath = listOf("/libs/my-commandapi-wrapper-1.0.jar")),
        )
    assertEquals(emptyList<ModeRejection>(), result)
  }

  @Test
  fun `unrelated classpath jars do not fire`() {
    val result =
        rejections(
            metadata =
                metadata(
                    runtimeClasspath =
                        listOf(
                            "/cache/paper-api-1.21.4-R0.1-SNAPSHOT.jar",
                            "/cache/kotlin-stdlib-2.3.20.jar",
                        )
                ),
        )
    assertEquals(emptyList<ModeRejection>(), result)
  }

  // ── paperplane.yml server plugins ───────────────────────────────────

  @Test
  fun `Modrinth slug in server plugins rejects hot-reload`() {
    val result =
        rejections(config = config(plugins = listOf(PluginDependency.modrinth("ProtocolLib"))))
    assertEquals(1, result.size)
    assertEquals("protocollib", result[0].ruleId)
    assertEquals("dev-server plugin 'protocollib' (paperplane.yml)", result[0].matchedBy)
  }

  @Test
  fun `local jar path in server plugins rejects hot-reload`() {
    val result =
        rejections(
            config = config(plugins = listOf(PluginDependency.local("./libs/CommandAPI-10.1.2.jar")))
        )
    assertEquals(1, result.size)
    assertEquals("commandapi", result[0].ruleId)
  }

  @Test
  fun `server plugins list scan works without metadata`() {
    // A broken build has no metadata.json — the config-side sources must still be scanned.
    val result =
        rejections(
            config = config(plugins = listOf(PluginDependency.modrinth("commandapi"))),
            metadata = null,
        )
    assertEquals(1, result.size)
    assertEquals("commandapi", result[0].ruleId)
  }

  // ── server plugins directory ────────────────────────────────────────

  @Test
  fun `hand-dropped jar in the server plugins dir rejects hot-reload`() {
    val pluginsDir = File(tempDir, "plugins").apply { mkdirs() }
    File(pluginsDir, "ProtocolLib.jar").writeText("fake")
    val result = rejections(pluginsDir = pluginsDir)
    assertEquals(1, result.size)
    assertEquals("server plugins/ jar 'ProtocolLib'", result[0].matchedBy)
  }

  @Test
  fun `plugins dir scan ignores non-jar entries and tolerates a missing dir`() {
    val pluginsDir = File(tempDir, "plugins").apply { mkdirs() }
    File(pluginsDir, "commandapi-notes.txt").writeText("not a jar")
    File(pluginsDir, "commandapi-data").mkdirs()
    assertEquals(emptyList<ModeRejection>(), rejections(pluginsDir = pluginsDir))
    assertEquals(
        emptyList<ModeRejection>(),
        rejections(pluginsDir = File(tempDir, "does-not-exist")),
    )
  }

  // ── version floor ───────────────────────────────────────────────────

  @Test
  fun `Paper below the floor rejects hot-reload`() {
    val result = rejections(metadata = metadata(paperApiVersion = "1.19.2"))
    assertEquals(1, result.size)
    assertEquals(ModeSelector.VERSION_FLOOR_RULE_ID, result[0].ruleId)
    assertEquals("Paper 1.19.2", result[0].matchedBy)
    assertTrue(result[0].reason.contains("Hot-reload requires Paper 1.19.3+"))
  }

  @Test
  fun `Paper at and above the floor passes`() {
    for (version in listOf("1.19.3", "1.21.11")) {
      assertEquals(
          emptyList<ModeRejection>(),
          rejections(metadata = metadata(paperApiVersion = version)),
          "expected no floor rejection for $version",
      )
    }
  }

  @Test
  fun `config server version overrides the metadata api version for the floor`() {
    val result =
        rejections(
            config = config(serverVersion = "1.18.2"),
            metadata = metadata(paperApiVersion = "1.21.4"),
        )
    assertEquals(1, result.size)
    assertEquals(ModeSelector.VERSION_FLOOR_RULE_ID, result[0].ruleId)
  }

  @Test
  fun `floor fires from config version alone when metadata is absent`() {
    val result = rejections(config = config(serverVersion = "1.19.2"), metadata = null)
    assertEquals(1, result.size)
    assertEquals(ModeSelector.VERSION_FLOOR_RULE_ID, result[0].ruleId)
  }

  @Test
  fun `unparseable version abstains from the floor`() {
    // "unknown" is metadata.json's placeholder when no paper-api dependency was detected.
    // Supported-version validation elsewhere owns that failure; the floor must not misfire.
    assertEquals(
        emptyList<ModeRejection>(),
        rejections(metadata = metadata(paperApiVersion = "unknown")),
    )
    assertEquals(emptyList<ModeRejection>(), rejections(metadata = null))
  }

  // ── aggregation ─────────────────────────────────────────────────────

  @Test
  fun `multiple rules and the floor aggregate into one rejection list`() {
    val result =
        rejections(
            metadata =
                metadata(
                    depend = listOf("CommandAPI"),
                    softdepend = listOf("ProtocolLib"),
                    paperApiVersion = "1.19.2",
                ),
        )
    assertEquals(
        listOf("commandapi", "protocollib", ModeSelector.VERSION_FLOOR_RULE_ID),
        result.map { it.ruleId },
    )
  }

  @Test
  fun `one rule firing on several sources yields a single rejection, first source wins`() {
    val result =
        rejections(
            metadata =
                metadata(
                    depend = listOf("CommandAPI"),
                    runtimeClasspath = listOf("/cache/commandapi-bukkit-shade-10.1.2.jar"),
                ),
        )
    assertEquals(1, result.size)
    assertEquals("plugin.yml depend 'CommandAPI'", result[0].matchedBy)
  }
}
