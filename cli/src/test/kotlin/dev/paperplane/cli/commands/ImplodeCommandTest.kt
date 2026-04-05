package dev.paperplane.cli.commands

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImplodeCommandTest {

  @TempDir lateinit var tempDir: File

  private val command = ImplodeCommand()

  // ── removePaperplaneBlock ───────────────────────────────────────────

  @Test
  fun `removes paperplane PATH block from zshrc`() {
    val rcFile = File(tempDir, ".zshrc")
    rcFile.writeText(
        """
        # existing stuff
        export PATH="/usr/local/bin:${'$'}PATH"

        # paperplane
        export PATH="${'$'}HOME/.paperplane/bin:${'$'}PATH"

        # other tool
        export FOO=bar
        """
            .trimIndent() + "\n"
    )

    command.removePaperplaneBlock(rcFile)

    val result = rcFile.readText()
    assertFalse(result.contains("# paperplane"), "Marker should be removed")
    assertFalse(result.contains(".paperplane"), "PATH entry should be removed")
    assertTrue(result.contains("# existing stuff"), "Other content preserved")
    assertTrue(result.contains("# other tool"), "Other content preserved")
    assertTrue(result.contains("export FOO=bar"), "Other exports preserved")
  }

  @Test
  fun `removes paperplane block with fpath for zsh completions`() {
    val rcFile = File(tempDir, ".zshrc")
    rcFile.writeText(
        """
        # other config

        # paperplane
        export PATH="${'$'}HOME/.paperplane/bin:${'$'}PATH"
        fpath=("${'$'}HOME/.paperplane/completions" ${'$'}fpath)

        # end
        """
            .trimIndent() + "\n"
    )

    command.removePaperplaneBlock(rcFile)

    val result = rcFile.readText()
    assertFalse(result.contains("# paperplane"))
    assertFalse(result.contains(".paperplane"))
    assertFalse(result.contains("fpath"))
    assertTrue(result.contains("# other config"))
    assertTrue(result.contains("# end"))
  }

  @Test
  fun `removes paperplane block with bash completion source`() {
    val rcFile = File(tempDir, ".bashrc")
    rcFile.writeText(
        """
        # bash config

        # paperplane
        export PATH="${'$'}HOME/.paperplane/bin:${'$'}PATH"
        source "${'$'}HOME/.paperplane/completions/ppl.bash"

        alias ll='ls -la'
        """
            .trimIndent() + "\n"
    )

    command.removePaperplaneBlock(rcFile)

    val result = rcFile.readText()
    assertFalse(result.contains("# paperplane"))
    assertFalse(result.contains(".paperplane"))
    assertTrue(result.contains("alias ll"))
  }

  @Test
  fun `preserves file when no paperplane block exists`() {
    val rcFile = File(tempDir, ".zshrc")
    val original =
        """
        # my config
        export PATH="/usr/bin:${'$'}PATH"
        """
            .trimIndent() + "\n"
    rcFile.writeText(original)

    command.removePaperplaneBlock(rcFile)

    assertEquals(original, rcFile.readText())
  }

  @Test
  fun `handles multiple paperplane blocks`() {
    val rcFile = File(tempDir, ".zshrc")
    rcFile.writeText(
        """
        # first block
        # paperplane
        export PATH="${'$'}HOME/.paperplane/bin:${'$'}PATH"

        # some middle content
        export BAR=baz

        # paperplane
        export PATH="${'$'}HOME/.paperplane/bin:${'$'}PATH"

        # end
        """
            .trimIndent() + "\n"
    )

    command.removePaperplaneBlock(rcFile)

    val result = rcFile.readText()
    assertFalse(result.contains("# paperplane"))
    assertFalse(result.contains(".paperplane"))
    assertTrue(result.contains("# first block"))
    assertTrue(result.contains("# some middle content"))
    assertTrue(result.contains("export BAR=baz"))
    assertTrue(result.contains("# end"))
  }

  @Test
  fun `handles paperplane block at end of file without trailing newline`() {
    val rcFile = File(tempDir, ".zshrc")
    rcFile.writeText("# config\n# paperplane\nexport PATH=\"\$HOME/.paperplane/bin:\$PATH\"")

    command.removePaperplaneBlock(rcFile)

    val result = rcFile.readText()
    assertFalse(result.contains("# paperplane"))
    assertFalse(result.contains(".paperplane"))
    assertTrue(result.contains("# config"))
  }
}
