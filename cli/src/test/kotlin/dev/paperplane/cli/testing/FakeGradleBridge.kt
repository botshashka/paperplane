package dev.paperplane.cli.testing

import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

/**
 * Test fake [GradleBridge] that records every invocation and returns scripted results without
 * spawning a Gradle daemon. Construct one in tests, set the `next*` fields to script the outcome,
 * then assert on [calls] for the sequence of method invocations.
 *
 * Methods that aren't overridden delegate to the real implementation, but tests should set scripted
 * values for everything they exercise so no real subprocess fires.
 */
class FakeGradleBridge(
    projectDir: File,
    ui: TerminalUI,
    var nextBuildResult: Boolean = true,
    var nextCleanResult: Boolean = true,
    var nextFormatResult: GradleBridge.FormatResult = GradleBridge.FormatResult(success = true),
    var nextTestResult: Boolean = true,
    var nextMetadata: ProjectMetadata? =
        ProjectMetadata(
            jarPath = "build/libs/test.jar",
            paperApiVersion = "1.21.4",
            mainClass = "com.example.TestPlugin",
            pluginName = "TestPlugin",
            projectDir = "/fake/project",
            version = "1.0.0",
        ),
    var nextMetadataFast: ProjectMetadata? = nextMetadata,
    /**
     * Optional callback invoked inside [test] before it returns. Lets tests recreate JUnit XML
     * fixture files that the production code has already cleared, since `runTestsInBlock` wipes
     * stale results before invoking gradle.test().
     */
    var onTest: () -> Unit = {},
) : GradleBridge(projectDir, ui) {

  /**
   * Explicit override for [metadata]. When null, falls back to [nextMetadata] (non-null →
   * Success, null → PluginNotApplied). Set this directly to script [MetadataResult.TaskFailed].
   */
  var nextMetadataResult: MetadataResult? = null

  /** See [nextMetadataResult]. */
  var nextMetadataFastResult: MetadataResult? = null

  /** Ordered log of every method call, e.g. `["build", "metadata", "test(quiet=true)"]`. */
  val calls: MutableList<String> = mutableListOf()

  override fun build(): Boolean {
    calls += "build"
    return nextBuildResult
  }

  override fun clean(): Boolean {
    calls += "clean"
    return nextCleanResult
  }

  override fun format(check: Boolean): GradleBridge.FormatResult {
    calls += "format(check=$check)"
    return nextFormatResult
  }

  override fun test(quiet: Boolean, filter: String?): Boolean {
    calls += "test(quiet=$quiet, filter=$filter)"
    onTest()
    return nextTestResult
  }

  override fun metadata(): MetadataResult {
    calls += "metadata"
    return nextMetadataResult ?: deriveResult(nextMetadata)
  }

  override fun metadataFast(): MetadataResult {
    calls += "metadataFast"
    return nextMetadataFastResult ?: deriveResult(nextMetadataFast)
  }

  private fun deriveResult(meta: ProjectMetadata?): MetadataResult =
      meta?.let { MetadataResult.Success(it) } ?: MetadataResult.PluginNotApplied

  override fun doClose() {
    calls += "close"
    // no-op — never opened a real connection
  }
}
