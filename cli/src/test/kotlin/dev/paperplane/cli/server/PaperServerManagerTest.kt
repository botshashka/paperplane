package dev.paperplane.cli.server

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperServerManagerTest {

  @TempDir lateinit var tempDir: File

  private fun createManager(port: Int = 25566): PaperServerManager {
    val serverDir = File(tempDir, "server-$port")
    val cacheDir = File(tempDir, "cache")
    val downloader = PaperDownloader(cacheDir)
    return PaperServerManager(serverDir, downloader, port = port)
  }

  @Test
  fun `configure creates server files with correct port`() {
    val manager = createManager(25566)
    manager.configure()

    val props = File(manager.serverDir, "server.properties").readText()
    assertTrue(props.contains("server-port=25566"))
    assertTrue(props.contains("online-mode=false"))
    assertTrue(props.contains("accepts-transfers=true"))
    assertTrue(File(manager.serverDir, "eula.txt").exists())
    assertTrue(File(manager.serverDir, "bukkit.yml").exists())
    assertTrue(File(manager.serverDir, "spigot.yml").exists())
  }

  @Test
  fun `configure overwrites existing server properties`() {
    val manager = createManager(25566)
    manager.serverDir.mkdirs()
    // Simulate Paper having rewritten server.properties without accepts-transfers
    File(manager.serverDir, "server.properties").writeText("server-port=25566\nonline-mode=false\n")

    manager.configure()

    val props = File(manager.serverDir, "server.properties").readText()
    assertTrue(props.contains("accepts-transfers=true"), "should overwrite with new properties")
  }

  @Test
  fun `configure uses different port for each server`() {
    val blue = createManager(25566)
    val green = createManager(25567)
    blue.configure()
    green.configure()

    val blueProps = File(blue.serverDir, "server.properties").readText()
    val greenProps = File(green.serverDir, "server.properties").readText()
    assertTrue(blueProps.contains("server-port=25566"))
    assertTrue(greenProps.contains("server-port=25567"))
  }

  @Test
  fun `configureVelocityForwarding writes velocity secret`() {
    val manager = createManager()
    manager.configure()
    manager.configureVelocityForwarding("test-secret-123")

    val paperConfig = File(manager.serverDir, "config/paper-global.yml").readText()
    assertTrue(paperConfig.contains("enabled: true"))
    assertTrue(paperConfig.contains("secret: \"test-secret-123\""))
  }

  @Test
  fun `writeCompanionStatus creates json file`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("building")

    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
    assertTrue(statusFile.exists())
    val json = statusFile.readText()
    assertTrue(json.contains("\"state\":\"building\""))
  }

  @Test
  fun `writeCompanionStatus includes extra fields`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("ready", mapOf("duration" to "2.5s"))

    val json = File(manager.serverDir, ".paperplane/companion-status.json").readText()
    assertTrue(json.contains("\"state\":\"ready\""))
    assertTrue(json.contains("\"duration\":\"2.5s\""))
  }

  @Test
  fun `isRunning returns false when not started`() {
    val manager = createManager()
    assertFalse(manager.isRunning())
  }

  @Test
  fun `stop is safe when not started`() {
    val manager = createManager()
    // Should not throw
    manager.stop()
  }

  @Test
  fun `sendCommand is safe when not started`() {
    val manager = createManager()
    // Should not throw
    manager.sendCommand("test")
  }

  @Test
  fun `waitForSave returns true when flag file appears`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    File(manager.serverDir, ".paperplane").mkdirs()

    // Simulate companion writing the flag after a short delay
    Thread {
          Thread.sleep(100)
          File(manager.serverDir, ".paperplane/save-complete").writeText("done")
        }
        .start()

    val result = manager.waitForSave(timeoutMs = 3000)
    assertTrue(result)
    // Flag file should be cleaned up
    assertFalse(File(manager.serverDir, ".paperplane/save-complete").exists())
  }

  @Test
  fun `waitForSave returns false on timeout`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    File(manager.serverDir, ".paperplane").mkdirs()

    val start = System.currentTimeMillis()
    val result = manager.waitForSave(timeoutMs = 300)
    val elapsed = System.currentTimeMillis() - start

    assertFalse(result)
    assertTrue(elapsed >= 250, "Should have waited close to timeout, waited ${elapsed}ms")
  }

  @Test
  fun `waitForSave clears stale flag file`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    val flagFile = File(manager.serverDir, ".paperplane/save-complete")
    flagFile.parentFile.mkdirs()
    flagFile.writeText("stale")

    // Should clear stale flag and then timeout (no new flag written)
    val result = manager.waitForSave(timeoutMs = 300)
    // The stale flag was cleared, and no new flag appeared, so it should timeout
    assertFalse(result)
  }

  @Test
  fun `waitForReady returns false when not started`() {
    val manager = createManager()
    val result = manager.waitForReady()
    assertFalse(result)
  }

  // ── copyPlugin / stagePlugin tests ──────────────────────────────────

  @Test
  fun `copyPlugin creates target with correct content`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("jar-content-v1")

    manager.copyPlugin(sourceJar)

    val target = File(manager.serverDir, "plugins/myplugin.jar")
    assertTrue(target.exists())
    assertEquals("jar-content-v1", target.readText())
  }

  @Test
  fun `copyPlugin overwrites existing target`() {
    val manager = createManager()
    manager.configure()
    val pluginsDir = File(manager.serverDir, "plugins")
    File(pluginsDir, "myplugin.jar").writeText("old-content")

    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("new-content")
    manager.copyPlugin(sourceJar)

    assertEquals("new-content", File(pluginsDir, "myplugin.jar").readText())
  }

  @Test
  fun `copyPlugin leaves no temp file`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("jar-content")

    manager.copyPlugin(sourceJar)

    val pluginsDir = File(manager.serverDir, "plugins")
    val tempFiles = pluginsDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
    assertTrue(
        tempFiles.isEmpty(),
        "No .tmp files should remain, found: ${tempFiles.map { it.name }}",
    )
  }

  @Test
  fun `stagePlugin creates new file without overwriting original`() {
    val manager = createManager()
    manager.configure()
    val pluginsDir = File(manager.serverDir, "plugins")
    File(pluginsDir, "myplugin.jar").writeText("original-content")

    val sourceJar = File(tempDir, "myplugin.jar")
    sourceJar.writeText("new-content")
    val stagedPath = manager.stagePlugin(sourceJar)

    assertEquals(
        "original-content",
        File(pluginsDir, "myplugin.jar").readText(),
        "Original jar should be unchanged",
    )
    val stagedFile = File(stagedPath)
    assertTrue(stagedFile.exists(), "Staged file should exist at $stagedPath")
    assertEquals("new-content", stagedFile.readText())
  }

  @Test
  fun `stagePlugin returns absolute path to staged file`() {
    val manager = createManager()
    manager.configure()
    val sourceJar = File(tempDir, "myplugin-1.0.jar")
    sourceJar.writeText("content")

    val stagedPath = manager.stagePlugin(sourceJar)

    assertTrue(File(stagedPath).isAbsolute, "Staged path should be absolute")
    assertTrue(stagedPath.endsWith("myplugin-1.0.jar.new"), "Should end with .new suffix")
    assertTrue(File(stagedPath).exists(), "Staged file should exist")
  }

  // ── waitForReady tests ──────────────────────────────────────────────

  @Test
  fun `waitForReady detects flag file from companion`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    File(manager.serverDir, ".paperplane").mkdirs()

    // Start a dummy process so waitForReady doesn't bail early
    val proc = ProcessBuilder("sleep", "10").start()
    // Use reflection to set the process field
    val processField = PaperServerManager::class.java.getDeclaredField("process")
    processField.isAccessible = true
    processField.set(manager, proc)

    try {
      // Write flag file from background thread
      Thread {
            Thread.sleep(100)
            val flagFile = File(manager.serverDir, ".paperplane/server-ready")
            flagFile.parentFile.mkdirs()
            flagFile.writeText("ready")
          }
          .start()

      val result = manager.waitForReady()
      assertTrue(result)
    } finally {
      proc.destroyForcibly()
    }
  }
}
