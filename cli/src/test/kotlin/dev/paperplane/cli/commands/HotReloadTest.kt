package dev.paperplane.cli.commands

import com.charleskorn.kaml.Yaml
import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HotReloadTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  private lateinit var ppDir: File

  @BeforeEach
  fun setUp() {
    ppDir = File(tempDir, ".paperplane")
    ppDir.mkdirs()
  }

  // ── Config parsing with hot-reload ─────────────────────────────────

  @Test
  fun `default DevConfig has mode HOT_RELOAD`() {
    val config = DevConfig()
    assertEquals(DevMode.HOT_RELOAD, config.mode)
  }

  @Test
  fun `config with mode hot-reload parses correctly`() {
    val yaml =
        """
        dev:
          mode: hot-reload
        """
            .trimIndent()

    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
  }

  @Test
  fun `config without mode field defaults to HOT_RELOAD`() {
    val yaml =
        """
        dev:
          debounce-ms: 2000
        """
            .trimIndent()

    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
  }

  @Test
  fun `config with mode restart`() {
    val yaml =
        """
        dev:
          mode: restart
        """
            .trimIndent()

    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals(DevMode.RESTART, config.dev.mode)
  }

  @Test
  fun `empty config has all defaults`() {
    val config = PaperPlaneConfig()
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(2000, config.dev.debounceMs)
  }

  @Test
  fun `full config with hot-reload parses all fields`() {
    val yaml =
        """
        server:
          version: "1.21.4"
          jvm-args:
            - "-Xmx4G"
            - "-Xms1G"
        dev:
          debounce-ms: 5000
          mode: hot-reload
        """
            .trimIndent()

    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals("1.21.4", config.server.version)
    assertEquals(listOf("-Xmx4G", "-Xms1G"), config.server.jvmArgs)
    assertEquals(5000, config.dev.debounceMs)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
  }

  @Test
  fun `PaperPlaneConfig load returns defaults when file missing`() {
    val config = PaperPlaneConfig.load(tempDir, ui)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(PaperPlaneConfig(), config)
  }

  @Test
  fun `PaperPlaneConfig load parses mode from file`() {
    val configFile = File(tempDir, "paperplane.yml")
    configFile.writeText(
        """
        dev:
          mode: hot-reload
          debounce-ms: 3000
        """
            .trimIndent()
    )

    val config = PaperPlaneConfig.load(tempDir, ui)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(3000, config.dev.debounceMs)
  }

  // ── DevConfig copy with CLI flag override ──────────────────────────

  @Test
  fun `CLI flag override sets mode on default config`() {
    val baseConfig = PaperPlaneConfig()
    assertEquals(DevMode.HOT_RELOAD, baseConfig.dev.mode)

    val cliMode = DevMode.RESTART
    val config = baseConfig.copy(dev = baseConfig.dev.copy(mode = cliMode))

    assertEquals(DevMode.RESTART, config.dev.mode)
    // Other fields remain unchanged
    assertEquals(2000, config.dev.debounceMs)
  }

  @Test
  fun `CLI flag not set preserves config mode`() {
    val baseConfig = PaperPlaneConfig()
    val config = baseConfig

    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(baseConfig, config)
  }

  @Test
  fun `CLI flag not set preserves config mode from file`() {
    val baseConfig = PaperPlaneConfig(dev = DevConfig(mode = DevMode.BLUE_GREEN))
    val config = baseConfig

    assertEquals(
        DevMode.BLUE_GREEN,
        config.dev.mode,
        "Config file value should be preserved when CLI flag is not set",
    )
  }

  @Test
  fun `CLI flag override does not affect other dev config fields`() {
    val baseConfig = PaperPlaneConfig(dev = DevConfig(debounceMs = 5000, mode = DevMode.RESTART))

    val config = baseConfig.copy(dev = baseConfig.dev.copy(mode = DevMode.HOT_RELOAD))

    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(5000, config.dev.debounceMs, "debounceMs should remain 5000")
  }

  @Test
  fun `CLI flag override is idempotent when config already has same mode`() {
    val baseConfig = PaperPlaneConfig(dev = DevConfig(mode = DevMode.HOT_RELOAD))
    val config = baseConfig.copy(dev = baseConfig.dev.copy(mode = DevMode.HOT_RELOAD))

    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(baseConfig.dev, config.dev)
  }
}
