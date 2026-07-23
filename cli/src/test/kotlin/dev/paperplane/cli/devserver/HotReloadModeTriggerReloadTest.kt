package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integration coverage for [HotReloadMode.triggerReload]'s decision to (re)build the plugin JAR
 * before staging it. A rebuild runs only `classes` (compileOnly), never `jar`, so the staged JAR —
 * load-bearing only in JAR-fallback mode — must be regenerated whenever the host will actually load
 * it, or the reload silently runs stale code.
 *
 * [HotReloadModeBuildLoadRequestTest] pins the pure `needsJarBuild` predicate; this test pins the
 * wiring: that `triggerReload` actually calls `gradle.build()` under the right conditions.
 */
class HotReloadModeTriggerReloadTest {

  @TempDir lateinit var tempDir: File

  private val metadata =
      ProjectMetadata(
          jarPath = "build/libs/test.jar",
          paperApiVersion = "1.21.4",
          mainClass = "com.example.TestPlugin",
          pluginName = "TestPlugin",
          projectDir = "/proj",
          version = "1.0.0",
      )

  private fun fixtureAndMode(): Pair<DevSessionFixture, HotReloadMode> {
    val fixture = DevSessionFixture(tempDir)
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    return fixture to HotReloadMode(fixture.session, server)
  }

  /** Writes a stale jar at `projectDir/jarPath` so the "missing jar" branch doesn't confound. */
  private fun createBuiltJar() {
    File(tempDir, metadata.jarPath).apply {
      parentFile.mkdirs()
      writeText("stale jar")
    }
  }

  private fun dirModeMeta() =
      metadata.copy(
          classesDir = "/proj/build/classes/kotlin/main",
          classesDirs = listOf("/proj/build/classes/kotlin/main"),
      )

  @Test
  fun `JAR-fallback mode rebuilds the jar before staging even when it already exists`() {
    val (fixture, mode) = fixtureAndMode()
    createBuiltJar()
    // JAR fallback: no fast metadata, so the staged jar is the only thing the host loads.
    fixture.gradle.nextMetadataFast = null

    mode.triggerReload(metadata)

    assertTrue(fixture.gradle.calls.contains("build"), "JAR mode must regenerate the jar")
  }

  @Test
  fun `directory mode skips the jar build when the jar already exists`() {
    val (fixture, mode) = fixtureAndMode()
    createBuiltJar()
    fixture.gradle.nextMetadataFast = dirModeMeta()

    mode.triggerReload(metadata)

    assertFalse(
        fixture.gradle.calls.contains("build"),
        "directory mode loads from classesDirs and must not pay for a jar build",
    )
  }

  @Test
  fun `directory mode still builds the jar when it is missing`() {
    val (fixture, mode) = fixtureAndMode()
    // No built jar on disk this time.
    fixture.gradle.nextMetadataFast = dirModeMeta()

    mode.triggerReload(metadata)

    assertTrue(
        fixture.gradle.calls.contains("build"),
        "a missing jar must be built regardless of mode",
    )
  }
}
