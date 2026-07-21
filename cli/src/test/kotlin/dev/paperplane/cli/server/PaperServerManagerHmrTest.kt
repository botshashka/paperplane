package dev.paperplane.cli.server

import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperServerManagerHmrTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  private fun createManager(port: Int = 25566): PaperServerManager {
    val serverDir = File(tempDir, "server-$port")
    val cacheDir = File(tempDir, "cache")
    val downloader = PaperDownloader(cacheDir)
    return PaperServerManager(serverDir, downloader, ui, port = port)
  }

  // ── extractAgent ──────────────────────────────────────────────────

  @Test
  fun `extractAgent does not throw when agent resource is missing`() {
    val manager = createManager()
    manager.serverDir.mkdirs()

    // Should not throw even when the resource is not available in tests
    val agentJar = manager.extractAgent()
    assertNotNull(agentJar)
    assertTrue(agentJar.absolutePath.endsWith("paperplane-agent.jar"))
  }

  @Test
  fun `extractAgent creates parent directory`() {
    val manager = createManager()
    // serverDir does not exist yet

    manager.extractAgent()

    val ppDir = File(manager.serverDir, ".paperplane")
    assertTrue(ppDir.exists(), ".paperplane directory should be created")
  }
}
