package dev.paperplane.cli.commands

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.devserver.ReloadStrategy
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProtocolCompatTest {

  @TempDir lateinit var tempDir: File

  private val gson = Gson()

  private fun createManager(): PaperServerManager {
    val serverDir = File(tempDir, "server")
    val cacheDir = File(tempDir, "cache")
    return PaperServerManager(serverDir, PaperDownloader(cacheDir))
  }

  private fun readStatus(manager: PaperServerManager): JsonObject {
    val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
    return gson.fromJson(statusFile.readText(), JsonObject::class.java)
  }

  // -- Version negotiation --

  @Test
  fun `new CLI writes protocolVersion 2 in all status messages`() {
    val manager = createManager()
    manager.serverDir.mkdirs()

    manager.writeCompanionStatus("building")
    assertEquals(2, readStatus(manager).get("protocolVersion").asInt)

    manager.writeCompanionStatus("ready", mapOf("duration" to "1.5s"))
    assertEquals(2, readStatus(manager).get("protocolVersion").asInt)

    manager.writeCompanionStatus("error", mapOf("message" to "compilation failed"))
    assertEquals(2, readStatus(manager).get("protocolVersion").asInt)
  }

  @Test
  fun `v1 message without protocolVersion field is valid JSON`() {
    val v1Json = """{"state":"building"}"""
    val parsed = gson.fromJson(v1Json, JsonObject::class.java)
    assertEquals("building", parsed.get("state").asString)
    assertNull(parsed.get("protocolVersion"))
  }

  @Test
  fun `v2 message with buildOutputDirs array is valid JSON`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "reloading",
        mapOf(
            "buildOutputDirs" to listOf("/build/classes/kotlin/main", "/build/classes/java/main")
        ),
    )

    val json = readStatus(manager)
    assertEquals("reloading", json.get("state").asString)
    val dirs = json.getAsJsonArray("buildOutputDirs")
    assertNotNull(dirs)
    assertEquals(2, dirs.size())
    assertEquals("/build/classes/kotlin/main", dirs[0].asString)
  }

  @Test
  fun `v2 message with changedClasses array is valid JSON`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "reloading",
        mapOf("changedClasses" to listOf("com.example.MyPlugin", "com.example.MyListener")),
    )

    val json = readStatus(manager)
    val classes = json.getAsJsonArray("changedClasses")
    assertNotNull(classes)
    assertEquals(2, classes.size())
    assertEquals("com.example.MyPlugin", classes[0].asString)
  }

  @Test
  fun `v2 message with missing optional fields is valid JSON`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "reloading",
        mapOf("pluginName" to "MyPlugin", "jarFileName" to "myplugin.jar"),
    )

    val json = readStatus(manager)
    assertEquals("reloading", json.get("state").asString)
    assertEquals("MyPlugin", json.get("pluginName").asString)
    assertNull(json.get("changedClasses"))
  }

  @Test
  fun `v2 message with extra unknown fields is valid JSON for forward compat`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "building",
        mapOf("futureField" to "some-value", "anotherNewField" to 42),
    )

    val json = readStatus(manager)
    assertEquals("building", json.get("state").asString)
    assertEquals("some-value", json.get("futureField").asString)
    assertEquals(42, json.get("anotherNewField").asInt)
  }

  // -- Field compatibility --

  @Test
  fun `v1 message has state and string extras but no reloadStrategy`() {
    val v1Json = """{"state":"ready","duration":"3.2s"}"""
    val parsed = gson.fromJson(v1Json, JsonObject::class.java)
    assertEquals("ready", parsed.get("state").asString)
    assertEquals("3.2s", parsed.get("duration").asString)
    assertNull(parsed.get("reloadStrategy"))
  }

  @Test
  fun `v2 message with all reload fields has correct types`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "reloading",
        mapOf(
            "buildOutputDirs" to listOf("/build/classes/kotlin/main"),
            "changedClasses" to listOf("com.example.MyPlugin"),
            "reloadStrategy" to "hotswap",
        ),
    )

    val json = readStatus(manager)
    assertEquals("reloading", json.get("state").asString)
    assertTrue(json.get("buildOutputDirs").isJsonArray)
    assertTrue(json.get("changedClasses").isJsonArray)
    assertEquals("hotswap", json.get("reloadStrategy").asString)
    assertEquals(2, json.get("protocolVersion").asInt)
  }

  @Test
  fun `message with reloadStrategy jar parses correctly`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("reloading", mapOf("reloadStrategy" to "jar"))
    assertEquals("jar", readStatus(manager).get("reloadStrategy").asString)
  }

  @Test
  fun `message with reloadStrategy directory parses correctly`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("reloading", mapOf("reloadStrategy" to "directory"))
    assertEquals("directory", readStatus(manager).get("reloadStrategy").asString)
  }

  @Test
  fun `message with reloadStrategy hotswap parses correctly`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("reloading", mapOf("reloadStrategy" to "hotswap"))
    assertEquals("hotswap", readStatus(manager).get("reloadStrategy").asString)
  }

  // -- ReloadStrategy enum ↔ protocol compatibility --

  @Test
  fun `ReloadStrategy HOTSWAP key matches protocol hotswap string`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("reloading", mapOf("reloadStrategy" to ReloadStrategy.HOTSWAP.key))
    assertEquals("hotswap", readStatus(manager).get("reloadStrategy").asString)
  }

  @Test
  fun `ReloadStrategy DIRECTORY key matches protocol directory string`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus(
        "reloading",
        mapOf("reloadStrategy" to ReloadStrategy.DIRECTORY.key),
    )
    assertEquals("directory", readStatus(manager).get("reloadStrategy").asString)
  }

  @Test
  fun `ReloadStrategy JAR key matches protocol jar string`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    manager.writeCompanionStatus("reloading", mapOf("reloadStrategy" to ReloadStrategy.JAR.key))
    assertEquals("jar", readStatus(manager).get("reloadStrategy").asString)
  }
}
