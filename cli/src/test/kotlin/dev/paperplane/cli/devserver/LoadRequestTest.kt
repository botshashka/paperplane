package dev.paperplane.cli.devserver

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoadRequestTest {

  @TempDir lateinit var serverDir: File

  @Test
  fun `write creates file with correct json shape`() {
    val request =
        LoadRequest(
            requestId = "abc-123",
            jarPath = "/path/to/MyPlugin.jar",
            pluginName = "MyPlugin",
            classesDirs = listOf("/build/classes/main"),
            resourcesDir = "/build/resources/main",
            runtimeClasspath = listOf("/lib/dep.jar"),
            changedClasses = listOf("com.example.Foo"),
        )
    LoadRequest.write(serverDir, request)

    val target = LoadRequest.requestPath(serverDir)
    assertTrue(target.exists(), "load-request.json should be written")

    val json = JsonParser.parseString(target.readText()).asJsonObject
    assertEquals("abc-123", json.get("requestId").asString)
    assertEquals("/path/to/MyPlugin.jar", json.get("jarPath").asString)
    assertEquals("MyPlugin", json.get("pluginName").asString)
    assertEquals("com.example.Foo", json.getAsJsonArray("changedClasses").first().asString)
  }

  @Test
  fun `write is atomic - no tmp file remains`() {
    LoadRequest.write(serverDir, LoadRequest("id", "j.jar", "P"))
    val tmp = File(serverDir, ".paperplane/.load-request.tmp")
    assertFalse(tmp.exists(), "tmp file should be moved, not left behind")
  }

  @Test
  fun `write replaces existing file`() {
    LoadRequest.write(serverDir, LoadRequest("first", "/a.jar", "P"))
    LoadRequest.write(serverDir, LoadRequest("second", "/b.jar", "P"))
    val json = JsonParser.parseString(LoadRequest.requestPath(serverDir).readText()).asJsonObject
    assertEquals("second", json.get("requestId").asString)
    assertEquals("/b.jar", json.get("jarPath").asString)
  }

  @Test
  fun `roundtrip preserves all fields`() {
    val original =
        LoadRequest(
            requestId = LoadRequest.newId(),
            jarPath = "/x.jar",
            pluginName = "X",
            classesDirs = listOf("/a", "/b"),
            resourcesDir = "/r",
            runtimeClasspath = listOf("/lib1.jar", "/lib2.jar"),
            changedClasses = listOf("Foo", "Bar"),
        )
    LoadRequest.write(serverDir, original)
    val parsed =
        Gson()
            .fromJson(LoadRequest.requestPath(serverDir).readText(), LoadRequest::class.java)
    assertEquals(original, parsed)
  }

  @Test
  fun `newId produces unique values`() {
    val a = LoadRequest.newId()
    val b = LoadRequest.newId()
    assertNotEquals(a, b)
  }

  @Test
  fun `flag paths point at expected files`() {
    assertEquals(File(serverDir, ".paperplane/load-complete"), LoadRequest.completeFlag(serverDir))
    assertEquals(File(serverDir, ".paperplane/load-failed"), LoadRequest.failedFlag(serverDir))
  }
}
