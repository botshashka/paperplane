package dev.paperplane.cli.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VelocityManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `configure creates velocity toml with both backends`() {
        val manager = VelocityManager(tempDir)
        manager.configure(bluePort = 25566, greenPort = 25567, proxyPort = 25565)

        val toml = File(tempDir, "velocity.toml").readText()
        assertTrue(toml.contains("blue = \"127.0.0.1:25566\""))
        assertTrue(toml.contains("green = \"127.0.0.1:25567\""))
        assertTrue(toml.contains("bind = \"0.0.0.0:25565\""))
        assertTrue(toml.contains("try = [\"blue\", \"green\"]"))
        assertTrue(toml.contains("player-info-forwarding-mode = \"modern\""))
    }

    @Test
    fun `configure always overwrites velocity toml`() {
        val manager = VelocityManager(tempDir)
        File(tempDir, "velocity.toml").apply {
            parentFile.mkdirs()
            writeText("old-config")
        }

        manager.configure()

        val toml = File(tempDir, "velocity.toml").readText()
        assertFalse(toml.contains("old-config"))
        assertTrue(toml.contains("[servers]"))
    }

    @Test
    fun `configure writes forwarding secret`() {
        val manager = VelocityManager(tempDir)
        manager.configure()

        val secret = File(tempDir, "forwarding.secret").readText()
        assertEquals(manager.forwardingSecret, secret)
        assertTrue(secret.matches(Regex("[0-9a-f-]+")), "Should be a UUID")
    }

    @Test
    fun `configure writes initial active server json`() {
        val manager = VelocityManager(tempDir)
        manager.configure()

        val json = File(tempDir, "active-server.json").readText()
        assertTrue(json.contains("\"active\":\"blue\""))
        assertTrue(json.contains("\"transfer\":false"))
    }

    @Test
    fun `writeActiveServer writes transfer signal`() {
        val manager = VelocityManager(tempDir)
        tempDir.mkdirs()

        manager.writeActiveServer("green", transfer = true)

        val json = File(tempDir, "active-server.json").readText()
        assertTrue(json.contains("\"active\":\"green\""))
        assertTrue(json.contains("\"transfer\":true"))
    }

    @Test
    fun `writeActiveServer can clear transfer flag`() {
        val manager = VelocityManager(tempDir)
        tempDir.mkdirs()

        manager.writeActiveServer("green", transfer = true)
        manager.writeActiveServer("green", transfer = false)

        val json = File(tempDir, "active-server.json").readText()
        assertTrue(json.contains("\"transfer\":false"))
    }

    @Test
    fun `isRunning returns false when not started`() {
        val manager = VelocityManager(tempDir)
        assertFalse(manager.isRunning())
    }

    @Test
    fun `stop is safe when not started`() {
        val manager = VelocityManager(tempDir)
        manager.stop()
    }
}
