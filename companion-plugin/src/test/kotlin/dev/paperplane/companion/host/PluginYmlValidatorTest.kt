package dev.paperplane.companion.host

import java.util.logging.Logger
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PluginYmlValidatorTest {

  private lateinit var server: ServerMock

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  // ── compareApiVersions ───────────────────────────────────────────────

  @Test
  fun `compareApiVersions equal returns zero`() {
    assertEquals(0, PluginYmlValidator.compareApiVersions("1.21", "1.21"))
    assertEquals(0, PluginYmlValidator.compareApiVersions("1.21.4", "1.21.4"))
  }

  @Test
  fun `compareApiVersions newer beats older`() {
    assertTrue(PluginYmlValidator.compareApiVersions("1.21", "1.20") > 0)
    assertTrue(PluginYmlValidator.compareApiVersions("1.21.4", "1.21.0") > 0)
    assertTrue(PluginYmlValidator.compareApiVersions("2.0", "1.21") > 0)
  }

  @Test
  fun `compareApiVersions older loses to newer`() {
    assertTrue(PluginYmlValidator.compareApiVersions("1.13", "1.21") < 0)
    assertTrue(PluginYmlValidator.compareApiVersions("1.20", "1.20.6") < 0)
  }

  @Test
  fun `compareApiVersions extra segments still resolve correctly`() {
    // 1.21 vs 1.21.4 → 1.21.4 wins because the missing segment is treated as -1.
    assertTrue(PluginYmlValidator.compareApiVersions("1.21", "1.21.4") < 0)
  }

  @Test
  fun `compareApiVersions fails open, sorting a malformed segment as older`() {
    // Non-numeric segments compare as -1 so a malformed version never blocks a load with a
    // surprising error — it just sorts below any real version at that position.
    assertTrue(PluginYmlValidator.compareApiVersions("1.x", "1.21") < 0)
    assertTrue(PluginYmlValidator.compareApiVersions("1.21", "1.x") > 0)
    assertEquals(0, PluginYmlValidator.compareApiVersions("1.x", "1.y"))
  }

  // ── validate happy path ──────────────────────────────────────────────

  @Test
  fun `validate accepts a normal POSTWORLD plugin with no depends`() {
    val description = makeDescription("MyPlugin")
    val result = PluginYmlValidator.validate(description, server, logger())
    assertTrue(result is PluginYmlValidator.Result.Ok)
  }

  // ── load STARTUP rejection ───────────────────────────────────────────

  @Test
  fun `validate rejects load STARTUP with mode-restart hint`() {
    val description = makeDescription("MyPlugin", load = "STARTUP")
    val result = PluginYmlValidator.validate(description, server, logger())
    assertTrue(result is PluginYmlValidator.Result.Reject)
    val msg = (result as PluginYmlValidator.Result.Reject).message
    assertTrue(msg.contains("STARTUP"), "error must mention STARTUP")
    assertTrue(msg.contains("mode: restart"), "error must hint at mode: restart")
  }

  // ── api-version check ────────────────────────────────────────────────

  @Test
  fun `validate rejects when plugin api-version exceeds paper version`() {
    // Drive detectPaperApiVersion with a known, parseable bukkitVersion so the reject path runs
    // deterministically (asserting unconditionally, not "only if MockBukkit happened to parse").
    val paperServer = serverReporting("1.20.6-R0.1-SNAPSHOT")
    val description = makeDescription("MyPlugin", apiVersion = "1.21")

    val result = PluginYmlValidator.validate(description, paperServer, logger())

    assertTrue(
        result is PluginYmlValidator.Result.Reject,
        "api-version above the server's must be rejected",
    )
    val msg = (result as PluginYmlValidator.Result.Reject).message
    assertTrue(msg.contains("api-version"), "message must mention api-version: $msg")
    assertTrue(msg.contains("1.21"), "message must name the offending api-version: $msg")
  }

  @Test
  fun `validate accepts a plugin whose api-version is at or below the paper version`() {
    val paperServer = serverReporting("1.21.4-R0.1-SNAPSHOT")
    val description = makeDescription("MyPlugin", apiVersion = "1.13")

    val result = PluginYmlValidator.validate(description, paperServer, logger())

    assertTrue(result is PluginYmlValidator.Result.Ok)
  }

  @Test
  fun `validate skips the api-version check when the paper version is unparseable`() {
    // detectPaperApiVersion returns null for a non-version bukkitVersion, so even a wildly high
    // api-version must not be rejected on that basis.
    val paperServer = serverReporting("Mock")
    val description = makeDescription("MyPlugin", apiVersion = "9.99")

    val result = PluginYmlValidator.validate(description, paperServer, logger())

    assertTrue(result is PluginYmlValidator.Result.Ok)
  }

  // ── depend / softdepend ──────────────────────────────────────────────

  @Test
  fun `validate rejects when hard-depend is missing`() {
    val description = makeDescription("MyPlugin", depend = listOf("WorldGuard"))
    val result = PluginYmlValidator.validate(description, server, logger())
    assertTrue(result is PluginYmlValidator.Result.Reject)
    val msg = (result as PluginYmlValidator.Result.Reject).message
    assertTrue(msg.contains("WorldGuard"))
    assertTrue(msg.contains("not loaded"))
  }

  @Test
  fun `validate accepts when hard-depend is loaded`() {
    MockBukkit.createMockPlugin("WorldGuard")
    val description = makeDescription("MyPlugin", depend = listOf("WorldGuard"))
    val result = PluginYmlValidator.validate(description, server, logger())
    assertTrue(result is PluginYmlValidator.Result.Ok)
  }

  @Test
  fun `validate logs but does not reject missing soft-depend`() {
    val description = makeDescription("MyPlugin", softDepend = listOf("PlaceholderAPI"))
    val result = PluginYmlValidator.validate(description, server, logger())
    assertTrue(
        result is PluginYmlValidator.Result.Ok,
        "missing softdepend must not cause rejection",
    )
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private fun logger() = Logger.getLogger("PluginYmlValidatorTest")

  /** Wraps the MockBukkit server so `detectPaperApiVersion` sees a known [bukkitVersion]. */
  private fun serverReporting(bukkitVersion: String): org.bukkit.Server {
    // Delegate through a Server-typed reference (not the ServerMock type) so interface delegation
    // binds to Server's signatures rather than ServerMock's narrowed generic overrides.
    val base: org.bukkit.Server = server
    return object : org.bukkit.Server by base {
      override fun getBukkitVersion(): String = bukkitVersion
    }
  }

  private fun makeDescription(
      name: String,
      apiVersion: String? = null,
      load: String? = null,
      depend: List<String> = emptyList(),
      softDepend: List<String> = emptyList(),
  ): PluginDescriptionFile {
    val yaml = buildString {
      appendLine("name: $name")
      appendLine("main: com.example.$name")
      appendLine("version: 1.0")
      if (apiVersion != null) appendLine("api-version: '$apiVersion'")
      if (load != null) appendLine("load: $load")
      if (depend.isNotEmpty()) appendLine("depend: [${depend.joinToString(", ")}]")
      if (softDepend.isNotEmpty()) appendLine("softdepend: [${softDepend.joinToString(", ")}]")
    }
    return PluginDescriptionFile(yaml.byteInputStream())
  }
}
