package dev.paperplane.cli.commands

import com.charleskorn.kaml.Yaml
import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.PaperPlaneConfig
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HotReloadTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var ppDir: File

    @BeforeEach
    fun setUp() {
        ppDir = File(tempDir, ".paperplane")
        ppDir.mkdirs()
    }

    // ── Flag file polling logic ────────────────────────────────────────

    /**
     * Reimplements the same polling pattern used by DevCommand.waitForReloadResult
     * so we can test it in isolation without needing the private method.
     */
    private fun waitForReloadResult(ppDir: File, timeoutMs: Long): Boolean {
        val completeFlag = File(ppDir, "reload-complete")
        val failedFlag = File(ppDir, "reload-failed")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (completeFlag.exists()) {
                completeFlag.delete()
                return true
            }
            if (failedFlag.exists()) {
                failedFlag.readText()
                failedFlag.delete()
                return false
            }
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun `reload-complete flag returns true and is deleted`() {
        val completeFlag = File(ppDir, "reload-complete")
        completeFlag.writeText("done")

        val result = waitForReloadResult(ppDir, timeoutMs = 1000)

        assertTrue(result)
        assertFalse(completeFlag.exists(), "Flag file should be deleted after detection")
    }

    @Test
    fun `reload-failed flag returns false and is deleted`() {
        val failedFlag = File(ppDir, "reload-failed")
        failedFlag.writeText("ClassNotFoundException: com.example.MyPlugin")

        val result = waitForReloadResult(ppDir, timeoutMs = 1000)

        assertFalse(result)
        assertFalse(failedFlag.exists(), "Flag file should be deleted after detection")
    }

    @Test
    fun `no flags within timeout returns false`() {
        val start = System.currentTimeMillis()
        val result = waitForReloadResult(ppDir, timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - start

        assertFalse(result)
        assertTrue(elapsed >= 250, "Should have waited close to timeout, waited ${elapsed}ms")
    }

    @Test
    fun `both flags present - reload-complete wins because it is checked first`() {
        File(ppDir, "reload-complete").writeText("done")
        File(ppDir, "reload-failed").writeText("some error")

        val result = waitForReloadResult(ppDir, timeoutMs = 1000)

        assertTrue(result, "reload-complete should take priority when both flags exist")
        assertFalse(File(ppDir, "reload-complete").exists(), "Complete flag should be deleted")
        // Failed flag may still exist since we returned early
        // This matches the real behavior: first check wins
    }

    @Test
    fun `flag appears after delay is detected`() {
        Thread {
            Thread.sleep(150)
            File(ppDir, "reload-complete").writeText("done")
        }.start()

        val result = waitForReloadResult(ppDir, timeoutMs = 3000)

        assertTrue(result, "Should detect flag that appears after a short delay")
        assertFalse(File(ppDir, "reload-complete").exists())
    }

    @Test
    fun `failed flag appears after delay is detected`() {
        Thread {
            Thread.sleep(150)
            File(ppDir, "reload-failed").writeText("Plugin failed to enable")
        }.start()

        val result = waitForReloadResult(ppDir, timeoutMs = 3000)

        assertFalse(result, "Should detect failure flag that appears after a short delay")
        assertFalse(File(ppDir, "reload-failed").exists())
    }

    @Test
    fun `reload-failed flag content is readable before deletion`() {
        val errorMessage = "NoClassDefFoundError: org/bukkit/event/Listener"
        File(ppDir, "reload-failed").writeText(errorMessage)

        // Verify the content is there before polling consumes it
        assertEquals(errorMessage, File(ppDir, "reload-failed").readText())

        val result = waitForReloadResult(ppDir, timeoutMs = 1000)
        assertFalse(result)
        assertFalse(File(ppDir, "reload-failed").exists())
    }

    // ── Config parsing with hot-reload ─────────────────────────────────

    @Test
    fun `default DevConfig has hotReload false`() {
        val config = DevConfig()
        assertFalse(config.hotReload)
    }

    @Test
    fun `config with hot-reload true parses correctly`() {
        val yaml = """
            dev:
              hot-reload: true
        """.trimIndent()

        val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
        assertTrue(config.dev.hotReload)
    }

    @Test
    fun `config without hot-reload field defaults to false`() {
        val yaml = """
            dev:
              companion: true
        """.trimIndent()

        val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
        assertFalse(config.dev.hotReload)
    }

    @Test
    fun `config with hot-reload false explicit`() {
        val yaml = """
            dev:
              hot-reload: false
        """.trimIndent()

        val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
        assertFalse(config.dev.hotReload)
    }

    @Test
    fun `empty config has all defaults`() {
        val config = PaperPlaneConfig()
        assertFalse(config.dev.hotReload)
        assertTrue(config.dev.companion)
        assertFalse(config.dev.verboseServer)
        assertEquals(2000, config.dev.debounceMs)
        assertTrue(config.dev.proxy)
    }

    @Test
    fun `full config with hot-reload parses all fields`() {
        val yaml = """
            server:
              version: "1.21.4"
              jvm-args:
                - "-Xmx4G"
                - "-Xms1G"
            dev:
              companion: false
              verbose-server: true
              debounce-ms: 5000
              proxy: false
              hot-reload: true
        """.trimIndent()

        val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)
        assertEquals("1.21.4", config.server.version)
        assertEquals(listOf("-Xmx4G", "-Xms1G"), config.server.jvmArgs)
        assertFalse(config.dev.companion)
        assertTrue(config.dev.verboseServer)
        assertEquals(5000, config.dev.debounceMs)
        assertFalse(config.dev.proxy)
        assertTrue(config.dev.hotReload)
    }

    @Test
    fun `PaperPlaneConfig load returns defaults when file missing`() {
        val config = PaperPlaneConfig.load(tempDir)
        assertFalse(config.dev.hotReload)
        assertEquals(PaperPlaneConfig(), config)
    }

    @Test
    fun `PaperPlaneConfig load parses hot-reload from file`() {
        val configFile = File(tempDir, "paperplane.yml")
        configFile.writeText("""
            dev:
              hot-reload: true
              debounce-ms: 3000
        """.trimIndent())

        val config = PaperPlaneConfig.load(tempDir)
        assertTrue(config.dev.hotReload)
        assertEquals(3000, config.dev.debounceMs)
    }

    // ── DevConfig copy with CLI flag override ──────────────────────────

    @Test
    fun `CLI flag override enables hot-reload on default config`() {
        val baseConfig = PaperPlaneConfig()
        assertFalse(baseConfig.dev.hotReload)

        val hotReload = true
        val config = if (hotReload) baseConfig.copy(dev = baseConfig.dev.copy(hotReload = true)) else baseConfig

        assertTrue(config.dev.hotReload)
        // Other fields remain unchanged
        assertTrue(config.dev.companion)
        assertFalse(config.dev.verboseServer)
        assertEquals(2000, config.dev.debounceMs)
        assertTrue(config.dev.proxy)
    }

    @Test
    fun `CLI flag not set preserves config hot-reload false`() {
        val baseConfig = PaperPlaneConfig()
        val hotReload = false
        val config = if (hotReload) baseConfig.copy(dev = baseConfig.dev.copy(hotReload = true)) else baseConfig

        assertFalse(config.dev.hotReload)
        assertEquals(baseConfig, config)
    }

    @Test
    fun `CLI flag not set preserves config hot-reload true from file`() {
        val baseConfig = PaperPlaneConfig(dev = DevConfig(hotReload = true))
        val hotReload = false
        val config = if (hotReload) baseConfig.copy(dev = baseConfig.dev.copy(hotReload = true)) else baseConfig

        assertTrue(config.dev.hotReload, "Config file value should be preserved when CLI flag is not set")
    }

    @Test
    fun `CLI flag override does not affect other dev config fields`() {
        val baseConfig = PaperPlaneConfig(
            dev = DevConfig(
                companion = false,
                verboseServer = true,
                debounceMs = 5000,
                proxy = false,
                hotReload = false
            )
        )

        val hotReload = true
        val config = if (hotReload) baseConfig.copy(dev = baseConfig.dev.copy(hotReload = true)) else baseConfig

        assertTrue(config.dev.hotReload)
        assertFalse(config.dev.companion, "companion should remain false")
        assertTrue(config.dev.verboseServer, "verboseServer should remain true")
        assertEquals(5000, config.dev.debounceMs, "debounceMs should remain 5000")
        assertFalse(config.dev.proxy, "proxy should remain false")
    }

    @Test
    fun `CLI flag override is idempotent when config already has hot-reload true`() {
        val baseConfig = PaperPlaneConfig(dev = DevConfig(hotReload = true))
        val hotReload = true
        val config = if (hotReload) baseConfig.copy(dev = baseConfig.dev.copy(hotReload = true)) else baseConfig

        assertTrue(config.dev.hotReload)
        assertEquals(baseConfig.dev, config.dev)
    }
}
