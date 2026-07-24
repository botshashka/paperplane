package dev.paperplane.companion

import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
}
