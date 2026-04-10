package dev.paperplane.cli.commands

import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectTemplatesTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  @Test
  fun `generated paperplane yml parses cleanly`() {
    val yml = ProjectTemplates.paperplaneYml("1.21", "hot-reload", "auto")
    File(tempDir, "paperplane.yml").writeText(yml)

    val config = PaperPlaneConfig.load(tempDir, ui)

    assertEquals("1.21", config.server.version)
    // The template ships with the full server.properties replicated. Spot-check the
    // PaperPlane overrides and a vanilla default to confirm both came through.
    assertEquals("4", config.server.properties["view-distance"])
    assertEquals("flat", config.server.properties["level-type"])
    assertEquals("PaperPlane Dev Server", config.server.properties["motd"])
    assertEquals(
        "easy",
        config.server.properties["difficulty"],
        "vanilla default should pass through",
    )
    assertEquals(
        "survival",
        config.server.properties["gamemode"],
        "vanilla default should pass through",
    )
    assertTrue(config.server.ops.isEmpty())
  }
}
