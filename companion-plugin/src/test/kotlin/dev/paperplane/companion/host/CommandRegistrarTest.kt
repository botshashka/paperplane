package dev.paperplane.companion.host

import java.util.logging.Logger
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class CommandRegistrarTest {

  private lateinit var server: ServerMock
  private lateinit var plugin: JavaPlugin
  private lateinit var registrar: CommandRegistrar

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    plugin = MockBukkit.createMockPlugin("MyPlugin")
    registrar = CommandRegistrar(server, Logger.getLogger("CommandRegistrarTest"))
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  // ── empty / happy path ──────────────────────────────────────────────

  @Test
  fun `apply with no commands leaves registered set empty`() {
    val description = description()
    registrar.apply(plugin, description)
    assertTrue(registrar.registered().isEmpty())
  }

  @Test
  fun `apply with two commands registers both`() {
    val description = description("fly" to mapOf<String, Any>(), "tp" to mapOf<String, Any>())
    registrar.apply(plugin, description)
    assertEquals(setOf("fly", "tp"), registrar.registered())
  }

  // ── command properties propagate ────────────────────────────────────

  @Test
  fun `apply sets description usage permission and aliases`() {
    val description =
        description(
            "fly" to
                mapOf(
                    "description" to "Toggle flight",
                    "usage" to "/fly",
                    "permission" to "myplugin.fly",
                    "permission-message" to "no",
                    "aliases" to listOf("flight", "f"),
                ),
        )
    registrar.apply(plugin, description)

    val cmd = server.commandMap.getCommand("fly")!!
    assertEquals("Toggle flight", cmd.description)
    assertEquals("/fly", cmd.usage)
    assertEquals("myplugin.fly", cmd.permission)
    assertEquals(listOf("flight", "f"), cmd.aliases)
  }

  // ── diff: add, remove, no-churn ─────────────────────────────────────

  @Test
  fun `second apply with same commands does not re-register them`() {
    val d1 = description("fly" to mapOf<String, Any>())
    registrar.apply(plugin, d1)
    val firstInstance = server.commandMap.getCommand("fly")
    registrar.apply(plugin, d1)
    val secondInstance = server.commandMap.getCommand("fly")
    assertTrue(
        firstInstance === secondInstance,
        "Command instance must be reused across no-op applies (no churn).",
    )
  }

  @Test
  fun `apply removing a command unregisters it`() {
    val d1 = description("fly" to mapOf<String, Any>(), "tp" to mapOf<String, Any>())
    registrar.apply(plugin, d1)
    assertEquals(setOf("fly", "tp"), registrar.registered())

    val d2 = description("fly" to mapOf<String, Any>())
    registrar.apply(plugin, d2)
    assertEquals(setOf("fly"), registrar.registered())
    assertEquals(null, server.commandMap.getCommand("tp"))
  }

  @Test
  fun `apply adding a command registers it`() {
    registrar.apply(plugin, description("fly" to mapOf<String, Any>()))
    registrar.apply(plugin, description("fly" to mapOf<String, Any>(), "tp" to mapOf<String, Any>()))
    assertEquals(setOf("fly", "tp"), registrar.registered())
  }

  // ── clear ───────────────────────────────────────────────────────────

  @Test
  fun `clear removes all registered commands`() {
    registrar.apply(plugin, description("fly" to mapOf<String, Any>(), "tp" to mapOf<String, Any>()))
    registrar.clear()
    assertTrue(registrar.registered().isEmpty())
    assertEquals(null, server.commandMap.getCommand("fly"))
    assertEquals(null, server.commandMap.getCommand("tp"))
  }

  @Test
  fun `clear is idempotent`() {
    registrar.apply(plugin, description("fly" to mapOf<String, Any>()))
    registrar.clear()
    registrar.clear()
    assertTrue(registrar.registered().isEmpty())
  }

  // ── case insensitivity ──────────────────────────────────────────────

  @Test
  fun `command names are normalized to lowercase`() {
    registrar.apply(plugin, description("Fly" to mapOf<String, Any>()))
    assertTrue(registrar.registered().contains("fly"))
    assertFalse(registrar.registered().contains("Fly"))
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun description(vararg commands: Pair<String, Map<String, Any>>): PluginDescriptionFile {
    val yaml = buildString {
      appendLine("name: MyPlugin")
      appendLine("main: com.example.MyPlugin")
      appendLine("version: 1.0")
      if (commands.isNotEmpty()) {
        appendLine("commands:")
        for ((name, opts) in commands) {
          appendLine("  $name:")
          for ((key, value) in opts) {
            when (value) {
              is List<*> -> appendLine("    $key: [${value.joinToString(", ")}]")
              else -> appendLine("    $key: $value")
            }
          }
        }
      }
    }
    return PluginDescriptionFile(yaml.byteInputStream())
  }
}
