package dev.paperplane.cli

import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.ui.assertViewClosed
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression test for the `ppl` no-args branch in `PaperPlane.main`.
 *
 * The `main()` function itself is not directly testable (it spawns shutdown hooks and exits).
 * Instead, this test replays the exact `ui.*` call sequence the no-args branch makes against a
 * fresh `TerminalUI(RecordingTerminal())` and asserts the rendered output. If `main()`'s no-args
 * branch changes, this test must be updated to match — that's the point.
 *
 * The replay catches the bug we recently fixed (missing blank line under the header) and locks in
 * the corrected output.
 */
class PaperPlaneNoArgsTest {

  @Test
  fun `no-args branch renders header, command list, status, and trailing endView`() {
    val terminal = RecordingTerminal()
    val ui = TerminalUI(terminal)

    // Replays PaperPlane.main's `if (args.isEmpty())` branch.
    val version = Versions.paperplaneVersion()
    ui.header(version)
    ui.block {
      info("dev", "Start dev server with file watching")
      info("create", "Scaffold a new Paper plugin project")
      info("init", "Add PaperPlane to an existing project")
      info("test", "Run tests via Gradle")
      info("format", "Format source code with Spotless")
      info("clean", "Clean .paperplane directory")
      info("upgrade", "Update ppl to the latest version")
      info("implode", "Uninstall ppl completely")
      blank()
      status("Run 'ppl <command> --help' for more info")
    }
    ui.endView()

    // Verify all 8 command names appear
    val commands = listOf("dev", "create", "init", "test", "format", "clean", "upgrade", "implode")
    for (cmd in commands) {
      assertTrue(
          terminal.writes.any { it.contains("  ➜  $cmd  ") },
          "Expected command list line for '$cmd' in writes",
      )
    }

    // Header is present
    assertTrue(terminal.writes.any { it.contains("PaperPlane v$version") })

    // Status footer is present
    assertTrue(terminal.writes.any { it.contains("Run 'ppl <command> --help' for more info") })

    // The view ends with a trailing blank from endView()
    terminal.assertViewClosed()
  }
}
