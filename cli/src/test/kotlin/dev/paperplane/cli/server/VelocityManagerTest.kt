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
    fun `clearTransferComplete deletes confirmation file`() {
        val manager = VelocityManager(tempDir)
        tempDir.mkdirs()
        val file = File(tempDir, "transfer-complete")
        file.writeText("12345")
        assertTrue(file.exists())

        manager.clearTransferComplete()

        assertFalse(file.exists())
    }

    @Test
    fun `clearTransferComplete is safe when file missing`() {
        val manager = VelocityManager(tempDir)
        tempDir.mkdirs()
        // Should not throw
        manager.clearTransferComplete()
    }

    @Test
    fun `waitForTransferComplete returns true when file appears`() {
        val manager = VelocityManager(tempDir)
        tempDir.mkdirs()

        // Write the file from a background thread after a short delay
        Thread {
            Thread.sleep(100)
            File(tempDir, "transfer-complete").writeText(System.currentTimeMillis().toString())
        }.start()

        val result = manager.waitForTransferComplete(timeoutMs = 3000)
        assertTrue(result)
        // File should be cleaned up
        assertFalse(File(tempDir, "transfer-complete").exists())
    }

    @Test
    fun `waitForTransferComplete returns false on timeout`() {
        val manager = VelocityManager(tempDir)
        tempDir.mkdirs()

        val start = System.currentTimeMillis()
        val result = manager.waitForTransferComplete(timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - start

        assertFalse(result)
        assertTrue(elapsed >= 250, "Should have waited close to timeout, waited ${elapsed}ms")
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
