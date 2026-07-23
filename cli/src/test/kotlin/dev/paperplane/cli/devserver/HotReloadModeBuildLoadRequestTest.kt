package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ProjectMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pinpoint tests for the strategy-selection logic that decides what shape of [LoadRequest] the
 * companion receives on a full reload. (The instant lane runs before any reload, so a load request
 * always means a real host load.)
 *
 * Two strategies map to two contract guarantees:
 * - JAR fallback: classesDirs is empty (host MUST load from the staged JAR).
 * - DIRECTORY: classesDirs populated (host does the full classloader swap from build dirs).
 *
 * The companion interprets these shapes — these tests fix the contract on the CLI side so the two
 * halves can't drift.
 */
class HotReloadModeBuildLoadRequestTest {

  private val baseMetadata =
      ProjectMetadata(
          jarPath = "build/libs/sample.jar",
          paperApiVersion = "1.21",
          mainClass = "com.example.Sample",
          pluginName = "Sample",
          projectDir = "/proj",
          version = "1.0.0",
      )

  private fun fastMeta(classesDir: String = "/proj/build/classes/kotlin/main"): ProjectMetadata =
      baseMetadata.copy(
          classesDir = classesDir,
          classesDirs = listOf(classesDir),
          resourcesDir = "/proj/build/resources/main",
          runtimeClasspath = listOf("/lib/dep1.jar", "/lib/dep2.jar"),
      )

  // ── JAR fallback ────────────────────────────────────────────────────

  @Test
  fun `null fastMeta yields JAR fallback`() {
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = null,
            stagedJarPath = "/staged/sample.jar",
        )
    assertTrue(request.classesDirs.isEmpty(), "JAR fallback must have empty classesDirs")
    assertTrue(request.resourcesDir.isEmpty())
    assertTrue(request.runtimeClasspath.isEmpty())
    assertEquals("/staged/sample.jar", request.jarPath)
    assertEquals("Sample", request.pluginName)
  }

  @Test
  fun `fastMeta with empty classesDir yields JAR fallback`() {
    val emptyFast = baseMetadata.copy(classesDir = "", classesDirs = emptyList(), resourcesDir = "")
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = emptyFast,
            stagedJarPath = "/x.jar",
        )
    assertTrue(request.classesDirs.isEmpty())
  }

  // ── DIRECTORY ───────────────────────────────────────────────────────

  @Test
  fun `fastMeta with classes dirs yields DIRECTORY shape with full metadata propagation`() {
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = fastMeta(),
            stagedJarPath = "/staged/sample.jar",
        )
    assertEquals(listOf("/proj/build/classes/kotlin/main"), request.classesDirs)
    assertEquals("/proj/build/resources/main", request.resourcesDir)
    assertEquals(listOf("/lib/dep1.jar", "/lib/dep2.jar"), request.runtimeClasspath)
  }

  // ── needsJarBuild: keep the staged JAR fresh in JAR-fallback mode ────

  @Test
  fun `needsJarBuild is true in JAR-fallback mode so the staged jar is never stale`() {
    // JAR fallback = the staged jar is the only thing the host loads; it must reflect fresh code
    // even though the rebuild only ran `classes`.
    assertTrue(HotReloadMode.needsJarBuild(fastMeta = null, builtJarExists = true))
    assertTrue(
        HotReloadMode.needsJarBuild(
            fastMeta = baseMetadata.copy(classesDir = "", classesDirs = emptyList()),
            builtJarExists = true,
        )
    )
  }

  @Test
  fun `needsJarBuild is false in directory mode so fast reloads skip the jar task`() {
    // Directory mode: the host loads from classesDirs and never touches the jar, so there is no
    // reason to pay for a `jar` build.
    assertFalse(HotReloadMode.needsJarBuild(fastMeta = fastMeta(), builtJarExists = true))
  }

  @Test
  fun `needsJarBuild is true whenever the jar is missing regardless of mode`() {
    assertTrue(HotReloadMode.needsJarBuild(fastMeta = fastMeta(), builtJarExists = false))
    assertTrue(HotReloadMode.needsJarBuild(fastMeta = null, builtJarExists = false))
  }

  // ── identity / metadata propagation ─────────────────────────────────

  @Test
  fun `each call generates a unique requestId`() {
    val a = HotReloadMode.buildLoadRequest(baseMetadata, null, "/x.jar")
    val b = HotReloadMode.buildLoadRequest(baseMetadata, null, "/x.jar")
    assertTrue(a.requestId != b.requestId, "Each rebuild must mint a fresh requestId for dedup")
    assertTrue(a.requestId.isNotBlank())
  }
}
