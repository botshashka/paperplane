package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests the JSON protocol parsing logic used by BuildStatusBar when reading
 * companion-status.json written by the CLI. This validates the contract between
 * CLI and companion without needing a running Bukkit server.
 */
class BuildStatusBarRoutingTest {

    private val gson = Gson()

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun parse(json: String): JsonObject = gson.fromJson(json, JsonObject::class.java)

    // ── reloadStrategy parsing ──────────────────────────────────────────

    @Test
    fun `JSON with reloadStrategy hotswap parses correctly`() {
        val json = parse("""{"state":"reloading","reloadStrategy":"hotswap"}""")
        assertEquals("hotswap", json.get("reloadStrategy").asString)
    }

    @Test
    fun `JSON with reloadStrategy directory parses correctly`() {
        val json = parse("""{"state":"reloading","reloadStrategy":"directory"}""")
        assertEquals("directory", json.get("reloadStrategy").asString)
    }

    @Test
    fun `JSON with reloadStrategy jar parses correctly`() {
        val json = parse("""{"state":"reloading","reloadStrategy":"jar"}""")
        assertEquals("jar", json.get("reloadStrategy").asString)
    }

    @Test
    fun `JSON without reloadStrategy field returns null`() {
        val json = parse("""{"state":"reloading","pluginName":"test"}""")
        assertNull(json.get("reloadStrategy"))
    }

    // ── buildOutputDirs and changedClasses ───────────────────────────────

    @Test
    fun `JSON with buildOutputDirs array parses list`() {
        val json = parse("""{"buildOutputDirs":["/tmp/classes","/tmp/resources"]}""")
        val dirs = json.getAsJsonArray("buildOutputDirs")
        assertNotNull(dirs)
        assertEquals(2, dirs.size())
        assertEquals("/tmp/classes", dirs[0].asString)
        assertEquals("/tmp/resources", dirs[1].asString)
    }

    @Test
    fun `JSON with changedClasses array parses list`() {
        val json = parse("""{"changedClasses":["com.example.Foo","com.example.Bar"]}""")
        val classes = json.getAsJsonArray("changedClasses")
        assertNotNull(classes)
        assertEquals(2, classes.size())
        assertEquals("com.example.Foo", classes[0].asString)
        assertEquals("com.example.Bar", classes[1].asString)
    }

    @Test
    fun `JSON with missing buildOutputDirs returns null`() {
        val json = parse("""{"state":"reloading"}""")
        assertNull(json.getAsJsonArray("buildOutputDirs"))
    }

    @Test
    fun `JSON with missing changedClasses returns null`() {
        val json = parse("""{"state":"reloading"}""")
        assertNull(json.getAsJsonArray("changedClasses"))
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `malformed JSON throws JsonSyntaxException`() {
        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson("{invalid json!!", JsonObject::class.java)
        }
    }

    @Test
    fun `empty JSON object has null state field`() {
        val json = parse("""{}""")
        assertNull(json.get("state"))
    }

    @Test
    fun `JSON with protocolVersion is parseable and version field accessible`() {
        val json = parse("""{"protocolVersion":2,"state":"building"}""")
        assertEquals(2, json.get("protocolVersion").asInt)
        assertEquals("building", json.get("state").asString)
    }

    @Test
    fun `ROLLBACK_FAILED is a recognized result name in the protocol`() {
        // BuildStatusBar writes outcome.result.name to reload-failed flag file.
        // ROLLBACK_FAILED must be a valid enum value for the CLI to parse.
        val result = ReloadResult.valueOf("ROLLBACK_FAILED")
        assertEquals(ReloadResult.ROLLBACK_FAILED, result)
    }

    @Test
    fun `JSON with extra unknown fields parses without error`() {
        val json = parse("""{"state":"ready","unknownField":"someValue","anotherField":42}""")
        assertEquals("ready", json.get("state").asString)
        // Unknown fields are accessible but don't break parsing
        assertEquals("someValue", json.get("unknownField").asString)
        assertEquals(42, json.get("anotherField").asInt)
    }
}
