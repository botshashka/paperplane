package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.LeakDiagnosticsMode
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Content + atomicity coverage for [PaperServerManager.writeCompanionConfig] — the CLI→companion
 * config channel that carries the leak-diagnostics mode. Split out of `PaperServerManagerTest`
 * (which is already at the LargeClass ceiling); follows the same op-banlist-style shape.
 */
class PaperServerManagerCompanionConfigTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  private fun createManager(): PaperServerManager {
    val serverDir = File(tempDir, "server")
    val downloader = PaperDownloader(File(tempDir, "cache"))
    return PaperServerManager(serverDir, downloader, ui)
  }

  private fun read(manager: PaperServerManager): JsonObject =
      Gson()
          .fromJson(
              File(manager.serverDir, ".paperplane/companion-config.json").readText(),
              JsonObject::class.java,
          )

  @Test
  fun `writes leak-diagnostics summary and protocol version by default`() {
    val manager = createManager()
    manager.writeCompanionConfig(DevConfig())

    val json = read(manager)
    assertEquals(1, json.get("protocolVersion").asInt)
    assertEquals("summary", json.get("leakDiagnostics").asString)
  }

  @Test
  fun `writes the full mode`() {
    val manager = createManager()
    manager.writeCompanionConfig(DevConfig(leakDiagnostics = LeakDiagnosticsMode.FULL))
    assertEquals("full", read(manager).get("leakDiagnostics").asString)
  }

  @Test
  fun `writes the off mode`() {
    val manager = createManager()
    manager.writeCompanionConfig(DevConfig(leakDiagnostics = LeakDiagnosticsMode.OFF))
    assertEquals("off", read(manager).get("leakDiagnostics").asString)
  }

  @Test
  fun `leaves no tmp file behind`() {
    val manager = createManager()
    manager.writeCompanionConfig(DevConfig())

    assertTrue(File(manager.serverDir, ".paperplane/companion-config.json").exists())
    assertFalse(
        File(manager.serverDir, ".paperplane/.companion-config.json.tmp").exists(),
        "the tmp file must be moved into place, not left behind",
    )
  }

  @Test
  fun `overwrites a previous mode`() {
    val manager = createManager()
    manager.writeCompanionConfig(DevConfig(leakDiagnostics = LeakDiagnosticsMode.FULL))
    manager.writeCompanionConfig(DevConfig(leakDiagnostics = LeakDiagnosticsMode.OFF))
    assertEquals("off", read(manager).get("leakDiagnostics").asString)
  }
}
