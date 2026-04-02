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
}
