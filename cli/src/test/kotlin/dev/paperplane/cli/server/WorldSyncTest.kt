package dev.paperplane.cli.server

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class WorldSyncTest {

  @TempDir lateinit var tempDir: File

  private lateinit var src: File
  private lateinit var dst: File

  @BeforeEach
  fun setUp() {
    src = File(tempDir, "world").apply { mkdirs() }
    dst = File(tempDir, "target/world")
  }

  /** A minimal world tree: level.dat, region data, and lock files at two depths. */
  private fun populateWorld(dir: File) {
    File(dir, "region").mkdirs()
    File(dir, "level.dat").writeText("level")
    File(dir, "region/r.0.0.mca").writeText("region-data")
    File(dir, "session.lock").writeText("locked")
    File(dir, "region/stale.lock").writeText("locked")
  }

  /**
   * An argv builder standing in for the platform command, paired with [jvmCopyRunner] so tests
   * exercise the clone flow on every OS without exec'ing anything.
   */
  private val fakeArgv: (File, File) -> List<String> = { s, d -> listOf("clone", s.path, d.path) }

  /** "Clones" by plain-copying the argv's src to its dst, like a successful cp would. */
  private fun jvmCopyRunner(): (List<String>) -> Boolean = { argv ->
    File(argv[1]).copyRecursively(File(argv[2]))
  }

  // ── Clone path ──────────────────────────────────────────────────────

  @Test
  fun `a successful clone replaces the target wholesale and prunes lock files`() {
    populateWorld(src)
    dst.mkdirs()
    File(dst, "orphan.mca").writeText("stale") // clone = full-tree replacement: orphans die too

    val sync = WorldSync(fakeArgv, jvmCopyRunner())
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(WorldSync.Strategy.CLONE, sync.lastStrategy)
    assertEquals("level", File(dst, "level.dat").readText())
    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
    assertFalse(File(dst, "orphan.mca").exists(), "clone must not keep stale target files")
    assertFalse(File(dst, "session.lock").exists(), "lock files must not survive the clone")
    assertFalse(File(dst, "region/stale.lock").exists(), "nested lock files must be pruned too")
  }

  @Test
  fun `a clone lands in a fresh temp sibling and leaves none behind`() {
    populateWorld(src)
    val tmp = File(dst.parentFile, dst.name + WorldSync.CLONE_TMP_SUFFIX)
    tmp.mkdirs()
    File(tmp, "junk").writeText("from a crashed run")

    var cloneTarget: File? = null
    val sync =
        WorldSync(
            fakeArgv,
            { argv ->
              val target = File(argv[2])
              cloneTarget = target
              assertFalse(target.exists(), "the temp target must be cleaned before cloning")
              File(argv[1]).copyRecursively(target)
            },
        )
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(tmp, cloneTarget, "the clone must land in the temp sibling, not the target")
    assertFalse(tmp.exists(), "the temp sibling must be swapped away")
    assertTrue(File(dst, "level.dat").isFile)
  }

  @Test
  fun `a clone works when the target does not exist yet`() {
    populateWorld(src)

    val sync = WorldSync(fakeArgv, jvmCopyRunner())
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(WorldSync.Strategy.CLONE, sync.lastStrategy)
    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
  }

  // ── Fallback tier ───────────────────────────────────────────────────

  @Test
  fun `a failed clone falls back to incremental sync and stops retrying the exec`() {
    populateWorld(src)
    var execs = 0
    val sync =
        WorldSync(
            fakeArgv,
            {
              execs++
              false // e.g. ext4: reflink not supported
            },
        )

    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(WorldSync.Strategy.INCREMENTAL, sync.lastStrategy)
    assertEquals(
        1,
        execs,
        "clone-unsupported must be remembered — one doomed exec, not one per sync",
    )
    assertEquals("level", File(dst, "level.dat").readText())
    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
    assertFalse(File(dst, "session.lock").exists(), "the fallback honours the skip predicate")
  }

  @Test
  fun `a failed clone leaves the previous target intact for the fallback to reconcile`() {
    populateWorld(src)
    dst.mkdirs()
    File(dst, "region").mkdirs()
    File(dst, "region/r.0.0.mca").writeText("previous-region-data")
    File(dst, "orphan.mca").writeText("stale")

    val sync = WorldSync(fakeArgv, { false })
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
    assertFalse(File(dst, "orphan.mca").exists(), "the fallback removes orphans")
    val tmp = File(dst.parentFile, dst.name + WorldSync.CLONE_TMP_SUFFIX)
    assertFalse(tmp.exists(), "a failed clone must clean up its temp sibling")
  }

  @Test
  fun `a clone that reports success but produces nothing falls back instead of emptying the world`() {
    populateWorld(src)
    dst.mkdirs()
    File(dst, "level.dat").writeText("previous")

    // Success with no temp directory to swap in: the rename must fail and the fallback must run,
    // or the target is left deleted and the standby boots a world with no level.dat.
    val sync = WorldSync(fakeArgv, { true })
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(WorldSync.Strategy.INCREMENTAL, sync.lastStrategy)
    assertEquals("level", File(dst, "level.dat").readText())
    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
  }

  @Test
  fun `a clone command that cannot be exec'd is a failure, not a crash`() {
    populateWorld(src)

    // The real runProcess, pointed at a binary that does not exist: ProcessBuilder throws
    // IOException, which must surface as "clone failed" and route to the fallback.
    val sync = WorldSync({ s, d -> listOf("ppl-no-such-binary", s.path, d.path) })
    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(WorldSync.Strategy.INCREMENTAL, sync.lastStrategy)
    assertEquals("level", File(dst, "level.dat").readText())
  }

  @Test
  fun `without a platform clone command the incremental tier is used and nothing is exec'd`() {
    populateWorld(src)
    val sync = WorldSync(cloneArgv = null, runCommand = { error("nothing may be exec'd") })

    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(WorldSync.Strategy.INCREMENTAL, sync.lastStrategy)
    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
  }

  // ── Platform command selection ──────────────────────────────────────

  @Test
  fun `macOS clones with cp -c (APFS clonefile)`() {
    val argv = WorldSync.platformCloneArgv("Mac OS X")
    assertNotNull(argv)
    assertEquals(
        listOf("cp", "-c", "-R", src.path, dst.path),
        argv!!(src, dst),
    )
  }

  @Test
  fun `linux clones with cp --reflink=always so unsupported filesystems fail detectably`() {
    val argv = WorldSync.platformCloneArgv("Linux")
    assertNotNull(argv)
    assertEquals(
        listOf("cp", "-a", "--reflink=always", src.path, dst.path),
        argv!!(src, dst),
    )
  }

  @Test
  fun `windows has no clone command — the incremental tier is the supported path`() {
    assertNull(WorldSync.platformCloneArgv("Windows 11"))
  }

  @Test
  fun `unknown platforms get no clone command`() {
    assertNull(WorldSync.platformCloneArgv("SunOS"))
  }

  // ── Real platform behavior ──────────────────────────────────────────

  @Test
  fun `real platform sync produces an identical world tree minus lock files`() {
    populateWorld(src)
    val sync = WorldSync() // whatever this OS/filesystem provides: clone or incremental

    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals("level", File(dst, "level.dat").readText())
    assertEquals("region-data", File(dst, "region/r.0.0.mca").readText())
    assertFalse(File(dst, "session.lock").exists())
    assertFalse(File(dst, "region/stale.lock").exists())
  }

  @Test
  @EnabledOnOs(OS.MAC)
  fun `the clone path fires on APFS`() {
    populateWorld(src)
    val sync = WorldSync()

    sync.sync(src, dst, IncrementalSync.SKIP_LOCK_FILES)

    assertEquals(
        WorldSync.Strategy.CLONE,
        sync.lastStrategy,
        "macOS temp dirs are APFS — a real cp -c must have cloned here",
    )
  }
}
