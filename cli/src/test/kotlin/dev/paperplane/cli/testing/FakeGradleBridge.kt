package dev.paperplane.cli.testing

import dev.paperplane.cli.gradle.GradleBridge
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

  /** Ordered log of every method call, e.g. `["build", "metadata", "test(quiet=true)"]`. */
  val calls: MutableList<String> = mutableListOf()

  override fun build(): Boolean {
    calls += "build"
    return nextBuildResult
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

  override fun metadata(): ProjectMetadata? {
    calls += "metadata"
    return nextMetadata
  }

  override fun metadataFast(): ProjectMetadata? {
    calls += "metadataFast"
    return nextMetadataFast
  }

  override fun doClose() {
    calls += "close"
    // no-op — never opened a real connection
  }
}
