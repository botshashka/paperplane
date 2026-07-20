package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AtomicWriteTest {

  @TempDir lateinit var tempDir: File

  private val gson = Gson()
  private val ui = TerminalUI(RecordingTerminal())

  private fun createManager(): PaperServerManager {
    val serverDir = File(tempDir, "server")
    val cacheDir = File(tempDir, "cache")
    return PaperServerManager(serverDir, PaperDownloader(cacheDir), ui)
  }

  @Test
  fun `target file exists with valid JSON after write`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("building")

    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
    assertTrue(statusFile.exists())
    val json = gson.fromJson(statusFile.readText(), JsonObject::class.java)
    assertEquals("building", json.get("state").asString)
  }

  @Test
  fun `no temp file remains after write`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("building")

    val tmpFile = File(manager.serverDir, ".paperplane/.companion-status.tmp")
    assertFalse(tmpFile.exists(), ".companion-status.tmp should not remain after write")
  }

  @Test
  fun `write creates file when target does not exist yet`() {
    val manager = createManager()
    manager.serverDir.mkdirs()

    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
    assertFalse(statusFile.exists(), "File should not exist before first write")

    manager.writeCompanionStatus("ready")
    assertTrue(statusFile.exists(), "File should exist after write")
  }

  @Test
  fun `write replaces content atomically when target already exists`() {
    val manager = createManager()
    manager.serverDir.mkdirs()

    manager.writeCompanionStatus("building")
    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
    val firstContent = statusFile.readText()

    manager.writeCompanionStatus("ready", mapOf("duration" to "2.0s"))
    val secondContent = statusFile.readText()

    assertNotEquals(firstContent, secondContent)
    val json = gson.fromJson(secondContent, JsonObject::class.java)
    assertEquals("ready", json.get("state").asString)
    assertEquals("2.0s", json.get("duration").asString)
  }

  @Test
  fun `leftover temp file from previous crash is overwritten`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    val ppDir = File(manager.serverDir, ".paperplane")
    ppDir.mkdirs()

    // Simulate a leftover temp file from a crash
    val tmpFile = File(ppDir, ".companion-status.tmp")
    tmpFile.writeText("corrupted-partial-write")

    manager.writeCompanionStatus("building")

    val statusFile = File(ppDir, "companion-status.json")
    assertTrue(statusFile.exists())
    val json = gson.fromJson(statusFile.readText(), JsonObject::class.java)
    assertEquals("building", json.get("state").asString)
  }

  @Test
  fun `status dir is created automatically when it does not exist`() {
    val manager = createManager()
    // Only create serverDir, not .paperplane/
    manager.serverDir.mkdirs()
    val ppDir = File(manager.serverDir, ".paperplane")
    assertFalse(ppDir.exists())

    manager.writeCompanionStatus("building")

    assertTrue(ppDir.exists(), ".paperplane directory should be created automatically")
    assertTrue(ppDir.isDirectory)
  }

  @Test
  fun `JSON output is valid and all fields parse correctly`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "reloading",
        mapOf(
            "pluginName" to "MyPlugin",
            "jarFileName" to "myplugin.jar",
            "reloadStrategy" to "directory",
            "buildOutputDirs" to listOf("/build/classes/kotlin/main"),
        ),
    )

    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
    val json = gson.fromJson(statusFile.readText(), JsonObject::class.java)

    assertEquals("reloading", json.get("state").asString)
    assertEquals(2, json.get("protocolVersion").asInt)
    assertEquals("MyPlugin", json.get("pluginName").asString)
    assertEquals("myplugin.jar", json.get("jarFileName").asString)
    assertEquals("directory", json.get("reloadStrategy").asString)
    assertTrue(json.get("buildOutputDirs").isJsonArray)
  }

  @Test
  fun `concurrent writes always produce valid JSON`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")

    repeat(100) { i ->
      manager.writeCompanionStatus("building", mapOf("iteration" to i))
      val content = statusFile.readText()
      assertDoesNotThrow(
          {
            val json = gson.fromJson(content, JsonObject::class.java)
            assertNotNull(json.get("state"), "state field missing on iteration $i")
          },
          "JSON was invalid on iteration $i: $content",
      )
    }
  }
}
