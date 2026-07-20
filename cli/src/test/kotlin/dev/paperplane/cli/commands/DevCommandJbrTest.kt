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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevCommandJbrTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  // ── DevConfig jbr field parsing ────────────────────────────────────

  @Test
  fun `default DevConfig has jbr auto`() {
    val config = DevConfig()
    assertEquals("auto", config.jbr)
  }

  @Test
  fun `DevConfig with jbr auto parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""jbr: auto""")
    assertEquals("auto", config.jbr)
  }

  @Test
  fun `DevConfig with jbr on parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""jbr: "on" """)
    assertEquals("on", config.jbr)
  }

  @Test
  fun `DevConfig with jbr off parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""jbr: "off" """)
    assertEquals("off", config.jbr)
  }

  @Test
  fun `DevConfig with jbr custom path parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""jbr: /opt/jbr-21/bin/java""")
    assertEquals("/opt/jbr-21/bin/java", config.jbr)
  }

  // ── YAML parsing of jbr within full config ─────────────────────────

  @Test
  fun `YAML with jbr on parses in full config`() {
    val yaml =
        """
        dev:
          jbr: "on"
        """
            .trimIndent()
    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals("on", config.dev.jbr)
  }

  @Test
  fun `YAML with jbr off parses in full config`() {
    val yaml =
        """
        dev:
          jbr: "off"
        """
            .trimIndent()
    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals("off", config.dev.jbr)
  }

  @Test
  fun `YAML with jbr custom path parses in full config`() {
    val yaml =
        """
        dev:
          jbr: /custom/path/to/java
        """
            .trimIndent()
    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
    assertEquals("/custom/path/to/java", config.dev.jbr)
  }

  @Test
  fun `full config with mode and jbr parses all fields`() {
    val yaml =
        """
        server:
          version: "1.21.4"
          jvm-args:
            - "-Xmx4G"
            - "-Xms2G"
        dev:
          mode: hot-reload
          jbr: "on"
          debounce-ms: 1500
        """
            .trimIndent()
    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)

    assertEquals("1.21.4", config.server.version)
    assertEquals(listOf("-Xmx4G", "-Xms2G"), config.server.jvmArgs)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals("on", config.dev.jbr)
    assertEquals(1500L, config.dev.debounceMs)
  }

  // ── Config file loading ────────────────────────────────────────────

  @Test
  fun `PaperPlaneConfig load returns defaults when file missing`() {
    val config = PaperPlaneConfig.load(tempDir, ui)
    assertEquals("auto", config.dev.jbr)
    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
  }

  @Test
  fun `PaperPlaneConfig load reads jbr from file`() {
    val configFile = File(tempDir, "paperplane.yml")
    configFile.writeText(
        """
        dev:
          jbr: "on"
        """
            .trimIndent()
    )

    val config = PaperPlaneConfig.load(tempDir, ui)
    assertEquals("on", config.dev.jbr)
  }
}
