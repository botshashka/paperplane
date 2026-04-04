package dev.paperplane.cli.server

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServerSyncTest {

  @TempDir lateinit var tempDir: File

  private lateinit var sourceDir: File
  private lateinit var targetDir: File

  @BeforeEach
  fun setup() {
    sourceDir = File(tempDir, "server-blue").apply { mkdirs() }
    targetDir = File(tempDir, "server-green").apply { mkdirs() }
  }

  @Test
  fun `syncs world directories`() {
    // Create world with some data
    File(sourceDir, "world/region").mkdirs()
    File(sourceDir, "world/region/r.0.0.mca").writeText("region-data")
    File(sourceDir, "world/playerdata").mkdirs()
    File(sourceDir, "world/playerdata/uuid.dat").writeText("player-data")
    File(sourceDir, "world_nether/region").mkdirs()
    File(sourceDir, "world_nether/region/r.0.0.mca").writeText("nether-data")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertEquals("region-data", File(targetDir, "world/region/r.0.0.mca").readText())
    assertEquals("player-data", File(targetDir, "world/playerdata/uuid.dat").readText())
    assertEquals("nether-data", File(targetDir, "world_nether/region/r.0.0.mca").readText())
  }

  @Test
  fun `patches port in server properties`() {
    File(sourceDir, "server.properties")
        .writeText("online-mode=false\nserver-port=25566\nmotd=test\n")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    val props = File(targetDir, "server.properties").readText()
    assertTrue(props.contains("server-port=25567"))
    assertFalse(props.contains("server-port=25566"))
    assertTrue(props.contains("online-mode=false"))
  }

  @Test
  fun `syncs config files`() {
    File(sourceDir, "bukkit.yml").writeText("bukkit-config")
    File(sourceDir, "spigot.yml").writeText("spigot-config")
    File(sourceDir, "config").mkdirs()
    File(sourceDir, "config/paper-global.yml").writeText("paper-config")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertEquals("bukkit-config", File(targetDir, "bukkit.yml").readText())
    assertEquals("spigot-config", File(targetDir, "spigot.yml").readText())
    assertEquals("paper-config", File(targetDir, "config/paper-global.yml").readText())
  }

  @Test
  fun `syncs server-level json files`() {
    File(sourceDir, "ops.json").writeText("[{\"name\":\"dev\"}]")
    File(sourceDir, "banned-players.json").writeText("[]")
    File(sourceDir, "whitelist.json").writeText("[]")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertEquals("[{\"name\":\"dev\"}]", File(targetDir, "ops.json").readText())
    assertEquals("[]", File(targetDir, "banned-players.json").readText())
    assertEquals("[]", File(targetDir, "whitelist.json").readText())
  }

  @Test
  fun `skips dev plugin jar and companion jar`() {
    File(sourceDir, "plugins").mkdirs()
    File(sourceDir, "plugins/my-plugin.jar").writeText("old-plugin")
    File(sourceDir, "plugins/paperplane-companion.jar").writeText("old-companion")
    File(sourceDir, "plugins/WorldEdit.jar").writeText("worldedit")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertFalse(File(targetDir, "plugins/my-plugin.jar").exists())
    assertFalse(File(targetDir, "plugins/paperplane-companion.jar").exists())
    assertTrue(File(targetDir, "plugins/WorldEdit.jar").exists())
    assertEquals("worldedit", File(targetDir, "plugins/WorldEdit.jar").readText())
  }

  @Test
  fun `syncs third-party plugin jars`() {
    File(sourceDir, "plugins").mkdirs()
    File(sourceDir, "plugins/WorldEdit.jar").writeText("worldedit-data")
    File(sourceDir, "plugins/EssentialsX.jar").writeText("essentials-data")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertEquals("worldedit-data", File(targetDir, "plugins/WorldEdit.jar").readText())
    assertEquals("essentials-data", File(targetDir, "plugins/EssentialsX.jar").readText())
  }

  @Test
  fun `syncs plugin data directories`() {
    File(sourceDir, "plugins/MyPlugin").mkdirs()
    File(sourceDir, "plugins/MyPlugin/config.yml").writeText("plugin-config")
    File(sourceDir, "plugins/MyPlugin/data").mkdirs()
    File(sourceDir, "plugins/MyPlugin/data/cities.yml").writeText("city-data")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertEquals("plugin-config", File(targetDir, "plugins/MyPlugin/config.yml").readText())
    assertEquals("city-data", File(targetDir, "plugins/MyPlugin/data/cities.yml").readText())
  }

  @Test
  fun `skips lock files`() {
    File(sourceDir, "world").mkdirs()
    File(sourceDir, "world/session.lock").writeText("locked")
    File(sourceDir, "server.lock").writeText("locked")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertFalse(
        File(targetDir, "world/session.lock").exists(),
        "world/session.lock should be skipped",
    )
    assertFalse(File(targetDir, "server.lock").exists(), "server.lock should be skipped")
  }

  @Test
  fun `overwrites stale target data`() {
    // Target has old data
    File(targetDir, "world/region").mkdirs()
    File(targetDir, "world/region/r.0.0.mca").writeText("old-data")
    File(targetDir, "world/region/r.1.1.mca").writeText("stale-chunk")

    // Source has new data
    File(sourceDir, "world/region").mkdirs()
    File(sourceDir, "world/region/r.0.0.mca").writeText("new-data")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertEquals("new-data", File(targetDir, "world/region/r.0.0.mca").readText())
    assertFalse(File(targetDir, "world/region/r.1.1.mca").exists(), "stale chunk should be deleted")
  }

  @Test
  fun `creates target directory if missing`() {
    val freshTarget = File(tempDir, "server-new")
    assertFalse(freshTarget.exists())

    File(sourceDir, "eula.txt").writeText("eula=true")

    ServerSync.syncServerState(sourceDir, freshTarget, 25567, "my-plugin.jar")

    assertTrue(freshTarget.exists())
    assertEquals("eula=true", File(freshTarget, "eula.txt").readText())
  }

  @Test
  fun `skips paperplane cli state directory`() {
    File(sourceDir, ".paperplane").mkdirs()
    File(sourceDir, ".paperplane/companion-status.json").writeText("""{"state":"saving"}""")
    File(sourceDir, "eula.txt").writeText("eula=true")

    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    assertFalse(File(targetDir, ".paperplane").exists(), ".paperplane dir should not be synced")
    assertTrue(File(targetDir, "eula.txt").exists(), "other files should still sync")
  }

  @Test
  fun `handles empty source directory`() {
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")
    // Should not throw, target should still exist
    assertTrue(targetDir.exists())
  }

  @Test
  fun `skips unchanged files during incremental sync`() {
    // Setup source
    File(sourceDir, "world/region").mkdirs()
    File(sourceDir, "world/region/r.0.0.mca").writeText("region-data")
    File(sourceDir, "eula.txt").writeText("eula=true")

    // First sync
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    // Record target timestamps
    val worldFile = File(targetDir, "world/region/r.0.0.mca")
    val eulaFile = File(targetDir, "eula.txt")
    val worldTimestamp = worldFile.lastModified()
    val eulaTimestamp = eulaFile.lastModified()

    // Wait a bit so any re-copy would produce a different timestamp
    Thread.sleep(50)

    // Second sync without modifying source
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    // Timestamps should be unchanged (files were not re-copied)
    assertEquals(
        worldTimestamp,
        worldFile.lastModified(),
        "world file should not have been re-copied",
    )
    assertEquals(eulaTimestamp, eulaFile.lastModified(), "eula file should not have been re-copied")
  }

  @Test
  fun `updates only modified files during incremental sync`() {
    // Setup source
    File(sourceDir, "world/region").mkdirs()
    File(sourceDir, "world/region/r.0.0.mca").writeText("region-data")
    File(sourceDir, "eula.txt").writeText("eula=true")

    // First sync
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    val worldFile = File(targetDir, "world/region/r.0.0.mca")
    val worldTimestamp = worldFile.lastModified()

    // Wait then modify only one file
    Thread.sleep(50)
    File(sourceDir, "eula.txt").writeText("eula=false")

    // Second sync
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    // Modified file should be updated
    assertEquals("eula=false", File(targetDir, "eula.txt").readText())
    // Unmodified file should not have been re-copied
    assertEquals(
        worldTimestamp,
        worldFile.lastModified(),
        "unmodified world file should not have been re-copied",
    )
  }

  @Test
  fun `removes files deleted from source`() {
    // Setup source with two files
    File(sourceDir, "world/region").mkdirs()
    File(sourceDir, "world/region/r.0.0.mca").writeText("region-data")
    File(sourceDir, "world/region/r.1.1.mca").writeText("extra-region")

    // First sync
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")
    assertTrue(File(targetDir, "world/region/r.1.1.mca").exists())

    // Delete a file from source
    File(sourceDir, "world/region/r.1.1.mca").delete()

    // Second sync
    ServerSync.syncServerState(sourceDir, targetDir, 25567, "my-plugin.jar")

    // Remaining file should still be there
    assertTrue(File(targetDir, "world/region/r.0.0.mca").exists())
    assertEquals("region-data", File(targetDir, "world/region/r.0.0.mca").readText())
    // Deleted file should be gone
    assertFalse(
        File(targetDir, "world/region/r.1.1.mca").exists(),
        "deleted file should be removed from target",
    )
  }
}
