package dev.paperplane.cli.testing

import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.devserver.DevSession
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

/**
 * Convenience builder for constructing a [DevSession] with all-fake dependencies and a
 * [RecordingTerminal]-backed [TerminalUI]. Tests construct one fixture, optionally tweak the
 * scripted return values via the public fields, then access [session] / [terminal] / [gradle] to
 * drive the dev-server modes under test.
 *
 * Example:
 * ```kotlin
 * val fixture = DevSessionFixture(tempDir).apply {
 *   gradle.nextBuildResult = false
 * }
 * val ui = fixture.ui
 * val session = fixture.session
 * RestartMode(session).run()
 * fixture.terminal.assertEmittedInOrder("Build failed", "Waiting for changes...")
 * ```
 *
 * Constructed against a `@TempDir` so the underlying file paths exist (helpers like
 * [PaperDownloader] are real but never invoke their network paths in tests).
 */
class DevSessionFixture(tempDir: File, config: PaperPlaneConfig = PaperPlaneConfig()) {
  val terminal: RecordingTerminal = RecordingTerminal()
  val ui: TerminalUI = TerminalUI(terminal)

  val ppDir: File = File(tempDir, ".paperplane").apply { mkdirs() }
  val projectDir: File = tempDir
  val downloader: PaperDownloader = FakePaperDownloader(File(ppDir, "cache"))
  val gradle: FakeGradleBridge = FakeGradleBridge(projectDir, ui)

  internal val session: DevSession =
      DevSession(
          config = config,
          ppDir = ppDir,
          gradle = gradle,
          downloader = downloader,
          projectDir = projectDir,
          ui = ui,
      )

  /**
   * Convenience to override the gradle metadata response with a single call. Use when the test
   * needs a specific plugin name or version in the dev-server output.
   */
  fun withMetadata(
      pluginName: String = "TestPlugin",
      version: String = "1.0.0",
      paperApiVersion: String = "1.21.4",
      jarPath: String = "build/libs/test.jar",
  ) = apply {
    val metadata =
        ProjectMetadata(
            jarPath = jarPath,
            paperApiVersion = paperApiVersion,
            mainClass = "com.example.$pluginName",
            pluginName = pluginName,
            projectDir = projectDir.absolutePath,
            version = version,
        )
    gradle.nextMetadata = metadata
    gradle.nextMetadataFast = metadata
  }
}
