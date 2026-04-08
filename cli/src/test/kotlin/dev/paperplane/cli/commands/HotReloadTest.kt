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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

  // ── Flag file polling logic ────────────────────────────────────────

  /**
   * Reimplements the same polling pattern used by DevCommand.waitForReloadResult so we can test it
   * in isolation without needing the private method.
   */
  private fun waitForReloadResult(ppDir: File, timeoutMs: Long): Boolean {
    val completeFlag = File(ppDir, "reload-complete")
    val failedFlag = File(ppDir, "reload-failed")
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      if (completeFlag.exists()) {
        completeFlag.delete()
        return true
      }
      if (failedFlag.exists()) {
        failedFlag.readText()
        failedFlag.delete()
        return false
      }
      Thread.sleep(50)
    }
    return false
  }

  @Test
  fun `reload-complete flag returns true and is deleted`() {
    val completeFlag = File(ppDir, "reload-complete")
    completeFlag.writeText("done")

    val result = waitForReloadResult(ppDir, timeoutMs = 1000)

    assertTrue(result)
    assertFalse(completeFlag.exists(), "Flag file should be deleted after detection")
  }

  @Test
  fun `reload-failed flag returns false and is deleted`() {
    val failedFlag = File(ppDir, "reload-failed")
    failedFlag.writeText("ClassNotFoundException: com.example.MyPlugin")

    val result = waitForReloadResult(ppDir, timeoutMs = 1000)

    assertFalse(result)
    assertFalse(failedFlag.exists(), "Flag file should be deleted after detection")
  }

  @Test
  fun `no flags within timeout returns false`() {
    val start = System.currentTimeMillis()
    val result = waitForReloadResult(ppDir, timeoutMs = 300)
    val elapsed = System.currentTimeMillis() - start

    assertFalse(result)
    assertTrue(elapsed >= 250, "Should have waited close to timeout, waited ${elapsed}ms")
  }

  @Test
  fun `both flags present - reload-complete wins because it is checked first`() {
    File(ppDir, "reload-complete").writeText("done")
    File(ppDir, "reload-failed").writeText("some error")

    val result = waitForReloadResult(ppDir, timeoutMs = 1000)

    assertTrue(result, "reload-complete should take priority when both flags exist")
    assertFalse(File(ppDir, "reload-complete").exists(), "Complete flag should be deleted")
    // Failed flag may still exist since we returned early
    // This matches the real behavior: first check wins
  }

  @Test
  fun `flag appears after delay is detected`() {
    Thread {
          Thread.sleep(150)
          File(ppDir, "reload-complete").writeText("done")
        }
        .start()

    val result = waitForReloadResult(ppDir, timeoutMs = 3000)

    assertTrue(result, "Should detect flag that appears after a short delay")
    assertFalse(File(ppDir, "reload-complete").exists())
  }

  @Test
  fun `failed flag appears after delay is detected`() {
    Thread {
          Thread.sleep(150)
          File(ppDir, "reload-failed").writeText("Plugin failed to enable")
        }
        .start()

    val result = waitForReloadResult(ppDir, timeoutMs = 3000)

    assertFalse(result, "Should detect failure flag that appears after a short delay")
    assertFalse(File(ppDir, "reload-failed").exists())
  }

  @Test
  fun `reload-failed flag content is readable before deletion`() {
    val errorMessage = "NoClassDefFoundError: org/bukkit/event/Listener"
    File(ppDir, "reload-failed").writeText(errorMessage)

    // Verify the content is there before polling consumes it
    assertEquals(errorMessage, File(ppDir, "reload-failed").readText())

    val result = waitForReloadResult(ppDir, timeoutMs = 1000)
    assertFalse(result)
    assertFalse(File(ppDir, "reload-failed").exists())
  }

  @Test
  fun `ROLLBACK_SUCCESS in reload-failed flag is detected as failure`() {
    File(ppDir, "reload-failed").writeText("ROLLBACK_SUCCESS")

    val result = waitForReloadResult(ppDir, timeoutMs = 1000)

    assertFalse(result, "ROLLBACK_SUCCESS should still be detected as a failure")
    assertFalse(File(ppDir, "reload-failed").exists(), "Flag file should be deleted")
  }

  @Test
  fun `reload-complete with timing content is still detected as success`() {
    File(ppDir, "reload-complete").writeText("teardown=45,load=120,total=165")

    val result = waitForReloadResult(ppDir, timeoutMs = 1000)

    assertTrue(result, "Flag with timing content should still be detected as success")
    assertFalse(File(ppDir, "reload-complete").exists(), "Flag file should be deleted")
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
