package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ClassChanges
import dev.paperplane.cli.gradle.ProjectMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pinpoint tests for the strategy-selection logic that decides what shape of [LoadRequest] the
 * companion receives on rebuild.
 *
 * Three strategies map to three contract guarantees:
 * - JAR fallback: classesDirs is empty (host MUST load from the staged JAR).
 * - HOTSWAP: classesDirs populated AND changedClasses populated (host can use Instrumentation).
 * - DIRECTORY: classesDirs populated AND changedClasses empty (host must do full classloader swap).
 *
 * The companion's `BuildStatusBar.handleRequest` interprets these shapes — these tests fix the
 * contract on the CLI side so the two halves can't drift.
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
            changes =
                ClassChanges(modified = listOf("X"), added = emptyList(), removed = emptyList()),
            stagedJarPath = "/staged/sample.jar",
        )
    assertTrue(request.classesDirs.isEmpty(), "JAR fallback must have empty classesDirs")
    assertTrue(request.changedClasses.isEmpty())
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
            changes = ClassChanges(emptyList(), emptyList(), emptyList()),
            stagedJarPath = "/x.jar",
        )
    assertTrue(request.classesDirs.isEmpty())
    assertTrue(request.changedClasses.isEmpty())
  }

  // ── HOTSWAP ─────────────────────────────────────────────────────────

  @Test
  fun `only modified classes yields HOTSWAP request`() {
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = fastMeta(),
            changes =
                ClassChanges(
                    modified = listOf("com.example.Foo", "com.example.Bar"),
                    added = emptyList(),
                    removed = emptyList(),
                ),
            stagedJarPath = "/staged/sample.jar",
        )
    assertEquals(listOf("com.example.Foo", "com.example.Bar"), request.changedClasses)
    assertEquals(listOf("/proj/build/classes/kotlin/main"), request.classesDirs)
    assertEquals("/proj/build/resources/main", request.resourcesDir)
    assertEquals(listOf("/lib/dep1.jar", "/lib/dep2.jar"), request.runtimeClasspath)
  }

  // ── DIRECTORY ───────────────────────────────────────────────────────

  @Test
  fun `added classes force DIRECTORY (no HOTSWAP)`() {
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = fastMeta(),
            changes =
                ClassChanges(
                    modified = listOf("com.example.Foo"),
                    added = listOf("com.example.NewClass"),
                    removed = emptyList(),
                ),
            stagedJarPath = "/staged/sample.jar",
        )
    assertTrue(request.changedClasses.isEmpty(), "Adding classes must disqualify HOTSWAP")
    assertTrue(request.classesDirs.isNotEmpty(), "DIRECTORY must keep classesDirs populated")
  }

  @Test
  fun `removed classes force DIRECTORY (no HOTSWAP)`() {
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = fastMeta(),
            changes =
                ClassChanges(
                    modified = emptyList(),
                    added = emptyList(),
                    removed = listOf("com.example.Old"),
                ),
            stagedJarPath = "/x.jar",
        )
    assertTrue(request.changedClasses.isEmpty())
    assertTrue(request.classesDirs.isNotEmpty())
  }

  @Test
  fun `no changes still emits DIRECTORY shape (empty changedClasses)`() {
    // Edge case: rebuild fired but actual diff is empty (debouncing artifact). The host should
    // do a no-op-ish DIRECTORY reload rather than a JAR fallback.
    val request =
        HotReloadMode.buildLoadRequest(
            metadata = baseMetadata,
            fastMeta = fastMeta(),
            changes = ClassChanges(emptyList(), emptyList(), emptyList()),
            stagedJarPath = "/x.jar",
        )
    assertTrue(request.changedClasses.isEmpty())
    assertTrue(request.classesDirs.isNotEmpty())
  }

  // ── identity / metadata propagation ─────────────────────────────────

  @Test
  fun `each call generates a unique requestId`() {
    val a =
        HotReloadMode.buildLoadRequest(
            baseMetadata,
            null,
            ClassChanges(emptyList(), emptyList(), emptyList()),
            "/x.jar",
        )
    val b =
        HotReloadMode.buildLoadRequest(
            baseMetadata,
            null,
            ClassChanges(emptyList(), emptyList(), emptyList()),
            "/x.jar",
        )
    assertTrue(a.requestId != b.requestId, "Each rebuild must mint a fresh requestId for dedup")
    assertTrue(a.requestId.isNotBlank())
  }
}
