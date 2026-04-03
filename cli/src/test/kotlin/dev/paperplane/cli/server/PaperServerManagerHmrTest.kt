package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PaperServerManagerHmrTest {

    @TempDir
    lateinit var tempDir: File

    private fun createManager(port: Int = 25566): PaperServerManager {
        val serverDir = File(tempDir, "server-$port")
        val cacheDir = File(tempDir, "cache")
        val downloader = PaperDownloader(cacheDir)
        return PaperServerManager(serverDir, downloader, port = port)
    }

    private fun parseJson(json: String): Map<String, Any> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return Gson().fromJson(json, type)
    }

    // ── writeCompanionStatus ──────────────────────────────────────────

    @Test
    fun `writeCompanionStatus with string values produces valid JSON`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeCompanionStatus("building")

        val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
        val parsed = parseJson(statusFile.readText())

        assertEquals("building", parsed["state"])
    }

    @Test
    fun `writeCompanionStatus with list values produces valid JSON array`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        val dirs = listOf("/tmp/classes/kotlin/main", "/tmp/resources")
        manager.writeCompanionStatus("reloading", mapOf("buildOutputDirs" to dirs))

        val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
        val parsed = parseJson(statusFile.readText())

        @Suppress("UNCHECKED_CAST")
        val outputDirs = parsed["buildOutputDirs"] as List<String>
        assertEquals(2, outputDirs.size)
        assertEquals("/tmp/classes/kotlin/main", outputDirs[0])
        assertEquals("/tmp/resources", outputDirs[1])
    }

    @Test
    fun `writeCompanionStatus includes protocolVersion 2`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeCompanionStatus("ready")

        val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
        val parsed = parseJson(statusFile.readText())

        // Gson deserializes numbers as Double by default
        assertEquals(2.0, parsed["protocolVersion"])
    }

    @Test
    fun `writeCompanionStatus with empty extra map has only state and protocolVersion`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeCompanionStatus("idle", emptyMap())

        val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
        val parsed = parseJson(statusFile.readText())

        assertEquals(2, parsed.size, "Should contain only state and protocolVersion, got: $parsed")
        assertEquals("idle", parsed["state"])
        assertNotNull(parsed["protocolVersion"])
    }

    @Test
    fun `atomic write leaves no temp file after completion`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeCompanionStatus("building")

        val ppDir = File(manager.serverDir, ".paperplane")
        val tempFiles = ppDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(tempFiles.isEmpty(), "No .tmp files should remain, found: ${tempFiles.map { it.name }}")
    }

    @Test
    fun `writeCompanionStatus overwrites previous status`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeCompanionStatus("building")
        manager.writeCompanionStatus("ready", mapOf("duration" to "1.5s"))

        val statusFile = File(manager.serverDir, ".paperplane/companion-status.json")
        val parsed = parseJson(statusFile.readText())

        assertEquals("ready", parsed["state"])
        assertEquals("1.5s", parsed["duration"])
    }

    // ── extractAgent ──────────────────────────────────────────────────

    @Test
    fun `extractAgent does not throw when agent resource is missing`() {
        val manager = createManager()
        manager.serverDir.mkdirs()

        // Should not throw even when the resource is not available in tests
        val agentJar = manager.extractAgent()
        assertNotNull(agentJar)
        assertTrue(agentJar.absolutePath.endsWith("paperplane-agent.jar"))
    }

    @Test
    fun `extractAgent creates parent directory`() {
        val manager = createManager()
        // serverDir does not exist yet

        manager.extractAgent()

        val ppDir = File(manager.serverDir, ".paperplane")
        assertTrue(ppDir.exists(), ".paperplane directory should be created")
    }

}
