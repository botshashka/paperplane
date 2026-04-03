package dev.paperplane.cli.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PaperServerManagerTest {

    @TempDir
    lateinit var tempDir: File

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
        assertTrue(File(manager.serverDir, "eula.txt").exists())
        assertTrue(File(manager.serverDir, "bukkit.yml").exists())
        assertTrue(File(manager.serverDir, "spigot.yml").exists())
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
    fun `writeOverlayStatus creates json file`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeOverlayStatus("building")

        val statusFile = File(manager.serverDir, ".paperplane/overlay-status.json")
        assertTrue(statusFile.exists())
        val json = statusFile.readText()
        assertTrue(json.contains("\"state\":\"building\""))
    }

    @Test
    fun `writeOverlayStatus includes extra fields`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        manager.writeOverlayStatus("ready", mapOf("duration" to "2.5s"))

        val json = File(manager.serverDir, ".paperplane/overlay-status.json").readText()
        assertTrue(json.contains("\"state\":\"ready\""))
        assertTrue(json.contains("\"duration\":\"2.5s\""))
    }

    @Test
    fun `copyCompanion removes stale overlay jar`() {
        val manager = createManager()
        manager.serverDir.mkdirs()
        val pluginsDir = File(manager.serverDir, "plugins")
        pluginsDir.mkdirs()
        val staleJar = File(pluginsDir, "paperplane-overlay.jar")
        staleJar.writeText("old-overlay")

        manager.copyCompanion()

        assertFalse(staleJar.exists(), "stale paperplane-overlay.jar should be deleted")
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
        }.start()

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
            }.start()

            val result = manager.waitForReady()
            assertTrue(result)
        } finally {
            proc.destroyForcibly()
        }
    }
}
