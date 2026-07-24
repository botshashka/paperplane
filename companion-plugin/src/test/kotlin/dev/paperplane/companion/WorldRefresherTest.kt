package dev.paperplane.companion

import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Drives [WorldRefresher] directly against MockBukkit. The world container is a temp dir the tests
 * populate with (fake) world files — the refresher only checks for `level.dat` presence; actually
 * reading region files is the server's job and exercised by the real-server E2E.
 */
class WorldRefresherTest {

  @TempDir lateinit var container: File

  private lateinit var server: ServerMock
  private lateinit var plugin: org.bukkit.plugin.java.JavaPlugin
  private lateinit var refresher: WorldRefresher

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    plugin = MockBukkit.createMockPlugin("PaperPlane")
    refresher = WorldRefresher(plugin, container)
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  private fun createWorldFiles(name: String) {
    File(container, name).mkdirs()
    File(container, "$name/level.dat").writeText("level")
  }

  // ── refresh guards ──────────────────────────────────────────────────

  @Test
  fun `a blank world name is refused`() {
    val outcome = refresher.refresh("")
    assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
  }

  @Test
  fun `the server's default world cannot be refreshed`() {
    server.addSimpleWorld("world")
    createWorldFiles("world")

    val outcome = refresher.refresh("world")

    val failed = assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
    assertTrue(
        failed.message.contains("default world"),
        "the refusal must explain the void-only constraint; got: ${failed.message}",
    )
  }

  @Test
  fun `a refresh without synced world files is refused, naming the container`() {
    val outcome = refresher.refresh("devworld")

    val failed = assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
    assertTrue(
        failed.message.contains("world sync"),
        "the refusal must point at the missing sync; got: ${failed.message}",
    )
  }

  // ── refresh behavior ────────────────────────────────────────────────

  @Test
  fun `a first refresh loads the world as a secondary world`() {
    server.addSimpleWorld("world") // the untouched void default
    createWorldFiles("devworld")

    val outcome = refresher.refresh("devworld")

    val ok = assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, outcome)
    assertEquals("devworld", ok.worldName)
    assertNotNull(server.getWorld("devworld"), "the world must be loaded")
    assertNotNull(server.getWorld("world"), "the default world must be untouched")
  }

  @Test
  fun `a refresh works without any default world present`() {
    createWorldFiles("devworld")
    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, refresher.refresh("devworld"))
  }

  @Test
  fun `a repeat refresh unloads the previous incarnation and loads a fresh one`() {
    server.addSimpleWorld("world")
    createWorldFiles("devworld")

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, refresher.refresh("devworld"))
    val first = server.getWorld("devworld")
    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, refresher.refresh("devworld"))
    val second = server.getWorld("devworld")

    assertNotNull(second)
    assertNotSame(first, second, "the previous incarnation must have been unloaded")
  }

  @Test
  fun `players standing in the previous incarnation are parked at the default spawn`() {
    val defaultWorld = server.addSimpleWorld("world")
    createWorldFiles("devworld")
    refresher.refresh("devworld")
    val player = server.addPlayer()
    player.teleport(server.getWorld("devworld")!!.spawnLocation)

    val outcome = refresher.refresh("devworld")

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, outcome)
    assertEquals(
        defaultWorld,
        player.location.world,
        "the player must have been parked in the default world so the unload could proceed",
    )
  }

  private fun writeUid(worldName: String, uuid: java.util.UUID) {
    java.io.DataOutputStream(File(container, "$worldName/uid.dat").outputStream()).use {
      it.writeLong(uuid.mostSignificantBits)
      it.writeLong(uuid.leastSignificantBits)
    }
  }

  @Test
  fun `a uid dat colliding with a loaded world is deleted so a fresh UUID is minted`() {
    val defaultWorld = server.addSimpleWorld("world")
    createWorldFiles("devworld")
    writeUid("devworld", defaultWorld.uid) // cloned from the live world — same UUID

    val outcome = refresher.refresh("devworld")

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, outcome)
    assertFalse(
        File(container, "devworld/uid.dat").exists(),
        "the colliding uid.dat must be removed so the server mints a fresh world UUID",
    )
  }

  @Test
  fun `a non-colliding uid dat is kept — the refreshed world keeps its stable UUID`() {
    server.addSimpleWorld("world")
    createWorldFiles("devworld")
    writeUid("devworld", java.util.UUID.randomUUID())

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, refresher.refresh("devworld"))
    assertTrue(File(container, "devworld/uid.dat").exists())
  }

  @Test
  fun `an unreadable uid dat is deleted rather than handed to the server`() {
    server.addSimpleWorld("world")
    createWorldFiles("devworld")
    File(container, "devworld/uid.dat").writeBytes(byteArrayOf(1, 2, 3)) // truncated

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, refresher.refresh("devworld"))
    assertFalse(File(container, "devworld/uid.dat").exists())
  }

  // ── warmup ──────────────────────────────────────────────────────────

  @Test
  fun `warmup loads and unloads a throwaway world and deletes its directory`() {
    val outcome = refresher.warmup()

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, outcome)
    assertNull(
        server.getWorld(WorldRefresher.WARMUP_WORLD_NAME),
        "the warmup world must not stay loaded",
    )
    assertFalse(
        File(container, WorldRefresher.WARMUP_WORLD_NAME).exists(),
        "the warmup world's directory must be deleted",
    )
  }

  @Test
  fun `warmup clears a leftover directory from a crashed run before loading`() {
    val leftover = File(container, WorldRefresher.WARMUP_WORLD_NAME)
    File(leftover, "region").mkdirs()
    File(leftover, "region/r.0.0.mca").writeText("stale")

    val outcome = refresher.warmup()

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, outcome)
    assertFalse(leftover.exists())
  }

  // ── Server refusals ─────────────────────────────────────────────────
  // MockBukkit always succeeds, so the refusal branches are only reachable through the
  // createWorld/unloadWorld seams — the same injection WorldSync uses for its clone exec.

  /** A refresher whose world calls are scripted rather than delegated to MockBukkit. */
  private fun refresherWith(
      createWorld: (org.bukkit.WorldCreator) -> org.bukkit.World? = { it.createWorld() },
      unloadWorld: (org.bukkit.World, Boolean) -> Boolean = { w, save ->
        server.unloadWorld(w, save)
      },
  ) = WorldRefresher(plugin, container, createWorld, unloadWorld)

  @Test
  fun `a refresh whose world the server refuses to load fails with the real cause`() {
    createWorldFiles("devworld")

    val outcome = refresherWith(createWorld = { null }).refresh("devworld")

    val failed = assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
    assertTrue(failed.message.contains("refused to load world 'devworld'"), failed.message)
  }

  @Test
  fun `a refresh whose previous incarnation will not unload fails instead of loading a second`() {
    server.addSimpleWorld("world")
    createWorldFiles("devworld")
    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, refresher.refresh("devworld"))
    val stuck = server.getWorld("devworld")

    val outcome = refresherWith(unloadWorld = { _, _ -> false }).refresh("devworld")

    val failed = assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
    assertTrue(failed.message.contains("could not unload"), failed.message)
    assertSame(
        stuck,
        server.getWorld("devworld"),
        "a refused unload must leave the previous incarnation in place, not stack a second one",
    )
  }

  @Test
  fun `a warmup the server refuses to load fails with the real cause`() {
    val outcome = refresherWith(createWorld = { null }).warmup()

    val failed = assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
    assertTrue(failed.message.contains("refused to load the warmup world"), failed.message)
  }

  @Test
  fun `a warmup that loads but will not unload is reported failed, not silently ok`() {
    val outcome = refresherWith(unloadWorld = { _, _ -> false }).warmup()

    val failed = assertInstanceOf(WorldRefresher.Outcome.Failed::class.java, outcome)
    assertTrue(failed.message.contains("could not unload the warmup world"), failed.message)
  }

  // ── Deletion retry chain ────────────────────────────────────────────

  /**
   * A refresher whose world "load" also drops a region file into the warmup directory and revokes
   * write on its parent — a file inside a non-writable directory cannot be unlinked, which is the
   * observable shape of Paper's async chunk I/O still holding the tree when the delete runs.
   * MockBukkit creates no directories of its own, so the test writes the tree itself.
   */
  private fun refresherWithUndeletableWarmupDir(region: File) =
      WorldRefresher(
          plugin,
          container,
          createWorld = { creator ->
            creator.createWorld()!!.also {
              region.mkdirs()
              File(region, "r.0.0.mca").writeText("late write")
              region.setWritable(false)
            }
          },
      )

  @Test
  @DisabledOnOs(OS.WINDOWS) // the setup revokes POSIX write permission to force a delete failure
  fun `a warmup directory that cannot be deleted yet is retried until it goes away`() {
    val dir = File(container, WorldRefresher.WARMUP_WORLD_NAME)
    val region = File(dir, "region")

    val outcome = refresherWithUndeletableWarmupDir(region).warmup()

    assertInstanceOf(WorldRefresher.Outcome.Ok::class.java, outcome)
    assertTrue(dir.exists(), "the synchronous delete must have failed, arming the retry")

    region.setWritable(true) // the transient condition clears...
    server.scheduler.performTicks(DELETE_RETRY_TICKS) // ...and the scheduled retry runs
    server.scheduler.waitAsyncTasksFinished()

    assertFalse(dir.exists(), "the retry chain must remove the directory once it can")
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `a warmup directory that never becomes deletable exhausts the retries without throwing`() {
    val dir = File(container, WorldRefresher.WARMUP_WORLD_NAME)
    val region = File(dir, "region")

    assertInstanceOf(
        WorldRefresher.Outcome.Ok::class.java,
        refresherWithUndeletableWarmupDir(region).warmup(),
    )
    // Far more ticks than the 10 retries need: the chain must terminate, not retry forever.
    server.scheduler.performTicks(DELETE_RETRY_TICKS * 20)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(dir.exists(), "nothing could delete it — and that is survivable, not fatal")
    region.setWritable(true) // so @TempDir cleanup can run
  }

  private companion object {
    /** Comfortably past one scheduled retry delay (10 ticks). */
    const val DELETE_RETRY_TICKS = 30L
  }
}
