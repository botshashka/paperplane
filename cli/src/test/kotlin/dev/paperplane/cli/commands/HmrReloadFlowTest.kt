package dev.paperplane.cli.commands

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.paperplane.cli.gradle.ClassChanges
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HmrReloadFlowTest {

    @TempDir
    lateinit var tempDir: File

    private fun parseJson(json: String): Map<String, Any> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return Gson().fromJson(json, type)
    }

    // ── ClassChanges noNewOrRemovedClasses ─────────────────────────────

    @Test
    fun `ClassChanges with only modified has noNewOrRemovedClasses true`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin", "com.example.Helper"),
            added = emptyList(),
            removed = emptyList()
        )
        assertTrue(changes.noNewOrRemovedClasses)
    }

    @Test
    fun `ClassChanges with added classes has noNewOrRemovedClasses false`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin"),
            added = listOf("com.example.NewClass"),
            removed = emptyList()
        )
        assertFalse(changes.noNewOrRemovedClasses)
    }

    @Test
    fun `ClassChanges with removed classes has noNewOrRemovedClasses false`() {
        val changes = ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = listOf("com.example.OldClass")
        )
        assertFalse(changes.noNewOrRemovedClasses)
    }

    @Test
    fun `ClassChanges with all empty lists has noNewOrRemovedClasses true`() {
        val changes = ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = emptyList()
        )
        assertTrue(changes.noNewOrRemovedClasses)
    }

    // ── Protocol JSON with reloadStrategy ─────────────────────────────

    @Test
    fun `protocol JSON with directory strategy and buildOutputDirs is valid`() {
        val statusExtra = mutableMapOf<String, Any>(
            "pluginName" to "TestPlugin",
            "jarFileName" to "testplugin-1.0.jar",
            "reloadStrategy" to "directory",
            "buildOutputDirs" to listOf("/tmp/classes/kotlin/main", "/tmp/resources")
        )
        val map = mutableMapOf<String, Any>("state" to "reloading", "protocolVersion" to 2)
        map.putAll(statusExtra)
        val json = Gson().toJson(map)

        val parsed = parseJson(json)

        assertEquals("reloading", parsed["state"])
        assertEquals("directory", parsed["reloadStrategy"])
        assertEquals("TestPlugin", parsed["pluginName"])
        assertEquals(2.0, parsed["protocolVersion"])
        @Suppress("UNCHECKED_CAST")
        val dirs = parsed["buildOutputDirs"] as List<String>
        assertEquals(2, dirs.size)
        assertFalse(parsed.containsKey("changedClasses"), "directory strategy should not include changedClasses")
    }

    @Test
    fun `protocol JSON with hotswap strategy and changedClasses is valid`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin", "com.example.Helper"),
            added = emptyList(),
            removed = emptyList()
        )

        val statusExtra = mutableMapOf<String, Any>(
            "pluginName" to "TestPlugin",
            "jarFileName" to "testplugin-1.0.jar",
            "reloadStrategy" to "hotswap",
            "buildOutputDirs" to listOf("/tmp/classes/kotlin/main")
        )
        statusExtra["changedClasses"] = changes.modified

        val map = mutableMapOf<String, Any>("state" to "reloading", "protocolVersion" to 2)
        map.putAll(statusExtra)
        val json = Gson().toJson(map)

        val parsed = parseJson(json)

        assertEquals("hotswap", parsed["reloadStrategy"])
        @Suppress("UNCHECKED_CAST")
        val changedClasses = parsed["changedClasses"] as List<String>
        assertEquals(2, changedClasses.size)
        assertTrue(changedClasses.contains("com.example.MyPlugin"))
        assertTrue(changedClasses.contains("com.example.Helper"))
    }

    @Test
    fun `protocol JSON without reloadStrategy defaults to jar behavior`() {
        // When reloadStrategy is absent (companion compat / fallback path),
        // the JSON contains pluginName, jarFileName, and pendingJar instead.
        val map = mutableMapOf<String, Any>(
            "state" to "reloading",
            "protocolVersion" to 2,
            "pluginName" to "TestPlugin",
            "jarFileName" to "testplugin-1.0.jar",
            "pendingJar" to "/tmp/server/.paperplane/testplugin-1.0.jar.new"
        )
        val json = Gson().toJson(map)

        val parsed = parseJson(json)

        assertEquals("reloading", parsed["state"])
        assertFalse(parsed.containsKey("reloadStrategy"), "jar fallback should not include reloadStrategy")
        assertTrue(parsed.containsKey("pendingJar"), "jar fallback should include pendingJar")
        assertFalse(parsed.containsKey("buildOutputDirs"), "jar fallback should not include buildOutputDirs")
    }

    // ── Protocol JSON with writeCompanionStatus end-to-end ────────────

    @Test
    fun `writeCompanionStatus directory strategy round-trips through file`() {
        val serverDir = File(tempDir, "server")
        serverDir.mkdirs()
        val cacheDir = File(tempDir, "cache")
        val manager = dev.paperplane.cli.server.PaperServerManager(
            serverDir, dev.paperplane.cli.server.PaperDownloader(cacheDir), port = 25566
        )

        val dirs = listOf("/tmp/classes/kotlin/main", "/tmp/resources")
        manager.writeCompanionStatus("reloading", mapOf(
            "pluginName" to "TestPlugin",
            "jarFileName" to "testplugin-1.0.jar",
            "reloadStrategy" to "directory",
            "buildOutputDirs" to dirs
        ))

        val statusFile = File(serverDir, ".paperplane/companion-status.json")
        assertTrue(statusFile.exists())
        val parsed = parseJson(statusFile.readText())

        assertEquals("reloading", parsed["state"])
        assertEquals("directory", parsed["reloadStrategy"])
        assertEquals(2.0, parsed["protocolVersion"])
        @Suppress("UNCHECKED_CAST")
        val outputDirs = parsed["buildOutputDirs"] as List<String>
        assertEquals(2, outputDirs.size)
    }

    @Test
    fun `writeCompanionStatus hotswap strategy with changedClasses round-trips through file`() {
        val serverDir = File(tempDir, "server")
        serverDir.mkdirs()
        val cacheDir = File(tempDir, "cache")
        val manager = dev.paperplane.cli.server.PaperServerManager(
            serverDir, dev.paperplane.cli.server.PaperDownloader(cacheDir), port = 25566
        )

        manager.writeCompanionStatus("reloading", mapOf(
            "pluginName" to "TestPlugin",
            "jarFileName" to "testplugin-1.0.jar",
            "reloadStrategy" to "hotswap",
            "buildOutputDirs" to listOf("/tmp/classes/kotlin/main"),
            "changedClasses" to listOf("com.example.MyPlugin", "com.example.Helper")
        ))

        val statusFile = File(serverDir, ".paperplane/companion-status.json")
        val parsed = parseJson(statusFile.readText())

        assertEquals("hotswap", parsed["reloadStrategy"])
        @Suppress("UNCHECKED_CAST")
        val changed = parsed["changedClasses"] as List<String>
        assertEquals(2, changed.size)
    }

    // ── Strategy selection logic ──────────────────────────────────────

    @Test
    fun `hotswap strategy chosen when only modified classes and non-empty`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin"),
            added = emptyList(),
            removed = emptyList()
        )
        val strategy = if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) "hotswap" else "directory"
        assertEquals("hotswap", strategy)
    }

    @Test
    fun `directory strategy chosen when new classes added`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin"),
            added = listOf("com.example.NewClass"),
            removed = emptyList()
        )
        val strategy = if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) "hotswap" else "directory"
        assertEquals("directory", strategy)
    }

    @Test
    fun `directory strategy chosen when classes removed`() {
        val changes = ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = listOf("com.example.OldClass")
        )
        val strategy = if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) "hotswap" else "directory"
        assertEquals("directory", strategy)
    }

    @Test
    fun `directory strategy chosen when no changes at all`() {
        val changes = ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = emptyList()
        )
        val strategy = if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) "hotswap" else "directory"
        assertEquals("directory", strategy)
    }
}
