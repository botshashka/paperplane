package dev.paperplane.cli.e2e

import dev.paperplane.cli.devserver.WorldRefreshFlow
import dev.paperplane.cli.server.IncrementalSync
import dev.paperplane.cli.server.LaunchSpec
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.server.WorldSync
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end proof of the world-refresh primitives against a REAL Paper server — the flushed save
 * round-trip, the CoW world sync, the warmup hook, and the secondary-world refresh RPC (first load
 * AND reload-with-unload), driven exactly as step 6's Fresh swap sequence will drive them. No dev
 * mode consumes these yet, so this test is their integration record.
 *
 * Gated behind `PPL_E2E=1` (downloads Paper, boots a real JVM, takes ~1–2 minutes):
 * ```
 * PPL_E2E=1 ./gradlew :cli:test --tests "dev.paperplane.cli.e2e.WorldRefreshE2ETest"
 * ```
 *
 * The session's protocol log is copied to `cli/build/e2e/protocol-log.ndjson` — the harvest source
 * for the captured-real replay fixtures (`fixtures/protocol-log-world-refresh.ndjson`).
 */
@EnabledIfEnvironmentVariable(named = "PPL_E2E", matches = "1")
class WorldRefreshE2ETest {

  companion object {
    /**
     * The newest release in the newest api-version line this CLI supports
     * ([dev.paperplane.cli.Versions.SUPPORTED_API_VERSIONS]) — deliberately not the newest Paper,
     * which is a 26.x build the CLI cannot yet resolve. Overridable with `PPL_E2E_PAPER_VERSION` so
     * CI single-sources it alongside the smoke test's `SMOKE_PAPER_VERSION`; point it at a newer
     * build to use this as a world-load drift canary.
     */
    private val PAPER_VERSION: String
      get() = System.getenv("PPL_E2E_PAPER_VERSION")?.takeIf { it.isNotBlank() } ?: "1.21.11"

    private const val PORT = 25599
  }

  @TempDir lateinit var tempDir: File

  /** Downloads Paper, configures the server dir (eula, companion), and starts the JVM. */
  private fun startRealServer(serverDir: File): PaperServerManager {
    val ui = TerminalUI(RecordingTerminal())
    // A persistent cache so repeated E2E runs don't re-download the Paper jar.
    val cacheDir = File(System.getProperty("java.io.tmpdir"), "ppl-e2e-paper-cache")
    val manager =
        PaperServerManager(serverDir, PaperDownloader(cacheDir), ui, PORT, protocolLog = true)
    val paperJar = manager.downloadServer(PAPER_VERSION)
    manager.cleanupStale()
    manager.configure()
    File(serverDir, "eula.txt").writeText("eula=true\n")
    manager.copyCompanion()
    val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
    val launch = LaunchSpec(javaBin, isJbr = false, jvmArgs = listOf("-Xmx2G"), attachAgent = false)
    manager.start(paperJar, launch)
    return manager
  }

  /**
   * Runs the warmup and waits out its directory deletion. Deletion is eventually-consistent —
   * Paper's async chunk I/O can drop a late file into the directory after the unload, which the
   * companion's retry chain then removes — so poll instead of asserting immediately.
   */
  private fun assertWarmupLeavesNothingBehind(flow: WorldRefreshFlow, serverDir: File) {
    val warmup = flow.warmUp()
    assertInstanceOf(WorldRefreshFlow.WarmupResult.Ok::class.java, warmup, "warmup: $warmup")
    val warmupDir = File(serverDir, "paperplane_warmup")
    val deleteDeadline = System.currentTimeMillis() + 10_000
    while (warmupDir.exists() && System.currentTimeMillis() < deleteDeadline) {
      Thread.sleep(200)
    }
    assertFalse(
        warmupDir.exists(),
        "the warmup world directory must be deleted; leftover: " +
            warmupDir.walkTopDown().joinToString { it.relativeTo(serverDir).path },
    )
  }

  @Test
  fun `flushed save, CoW sync, warmup, and secondary-world refresh against a real server`() {
    val serverDir = File(tempDir, "server")
    val manager = startRealServer(serverDir)
    try {
      assertTrue(manager.waitForReady(), "the real server must become ready")

      // 1. Flushed save over the socket: saveComplete only after World#save(flush=true) returns.
      assertEquals(
          PaperServerManager.SaveOutcome.Saved,
          manager.saveWorld(),
          "the flushed save must confirm",
      )

      // 2. CoW sync of the freshly-saved default world into a secondary world directory.
      val sync = WorldSync()
      sync.sync(
          File(serverDir, "world"),
          File(serverDir, "devworld"),
          IncrementalSync.SKIP_LOCK_FILES,
      )
      assertTrue(File(serverDir, "devworld/level.dat").isFile, "the sync must deliver level.dat")

      val flow = WorldRefreshFlow(manager.ipc)

      // 3. Warmup hook: throwaway world loads, unloads, and leaves no directory behind.
      assertWarmupLeavesNothingBehind(flow, serverDir)

      // 4. Secondary-world refresh: first load...
      val first = flow.refresh("devworld")
      val firstOk =
          assertInstanceOf(WorldRefreshFlow.RefreshResult.Ok::class.java, first, "first: $first")
      assertEquals("devworld", firstOk.world.worldName)

      assertFalse(firstOk.world.reloaded, "the first refresh loads, it does not reload")

      // ...and a repeat refresh, which must unload the previous incarnation and load anew. Without
      // this the second refresh could silently no-op (Bukkit handing back the already-loaded
      // world) and still report Ok.
      val second = flow.refresh("devworld")
      val secondOk =
          assertInstanceOf(WorldRefreshFlow.RefreshResult.Ok::class.java, second, "second: $second")
      assertTrue(
          secondOk.world.reloaded,
          "the repeat refresh must have unloaded the previous incarnation before loading",
      )

      // 5. A refresh whose world was never synced fails with the real cause, not a timeout.
      val missing = flow.refresh("never_synced")
      val failed =
          assertInstanceOf(
              WorldRefreshFlow.RefreshResult.Failed::class.java,
              missing,
              "missing: $missing",
          )
      assertTrue(failed.message.contains("world sync"), "got: ${failed.message}")
    } finally {
      manager.stop()
      // Harvest source for the captured-real replay fixtures.
      val log = File(serverDir, ".paperplane/protocol-log.ndjson")
      if (log.isFile) {
        val out = File("build/e2e/protocol-log.ndjson")
        out.parentFile.mkdirs()
        log.copyTo(out, overwrite = true)
      }
    }
  }
}
