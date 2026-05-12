package dev.paperplane.companion.host

import java.util.logging.Logger
import org.bukkit.command.PluginCommand
import org.bukkit.help.GenericCommandHelpTopic
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class CommandRegistrarTest {

  private lateinit var server: ServerMock
  private lateinit var plugin: JavaPlugin
  private lateinit var helpMapWriter: HelpMapWriter
  private lateinit var registrar: CommandRegistrar

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    plugin = MockBukkit.createMockPlugin("MyPlugin")
    helpMapWriter = HelpMapWriter(server.helpMap, ReflectionProbe.resolveHelpTopicsMap(server)!!)
    registrar = CommandRegistrar(server, helpMapWriter, Logger.getLogger("CommandRegistrarTest"))
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

  // ── reload across plugin instance change ────────────────────────────

  @Test
  fun `clear then apply on new plugin instance fully re-registers commands`() {
    // Reload semantics: same plugin name but a new JavaPlugin instance (fresh classloader).
    val v1 = MockBukkit.createMockPlugin("MyPlugin")
    val v1Registrar = CommandRegistrar(server, helpMapWriter, Logger.getLogger("v1"))
    v1Registrar.apply(v1, description("fly" to mapOf<String, Any>()))
    val v1Cmd = server.commandMap.getCommand("fly") as PluginCommand
    assertSame(v1, v1Cmd.plugin)

    v1Registrar.clear()

    val v2 = MockBukkit.createMockPlugin("MyPluginV2")
    val v2Registrar = CommandRegistrar(server, helpMapWriter, Logger.getLogger("v2"))
    v2Registrar.apply(v2, description("fly" to mapOf<String, Any>()))
    val v2Cmd = server.commandMap.getCommand("fly") as PluginCommand
    assertSame(
        v2,
        v2Cmd.plugin,
        "After clear+apply, the command must be owned by the new plugin instance",
    )
    assert(v1Cmd !== v2Cmd) { "Different registrar instances must produce different commands" }
  }

  @Test
  fun `aliases are unregistered fully on removal`() {
    registrar.apply(
        plugin,
        description("fly" to mapOf("aliases" to listOf("flight", "f"))),
    )
    // Aliases should be discoverable.
    assertNotNull(server.commandMap.getCommand("flight"))
    assertNotNull(server.commandMap.getCommand("f"))

    registrar.apply(plugin, description())
    assertNull(server.commandMap.getCommand("fly"))
    assertNull(
        server.commandMap.getCommand("flight"),
        "alias must be removed alongside primary name",
    )
    assertNull(server.commandMap.getCommand("f"), "alias must be removed alongside primary name")
  }

  // ── help-map integration ────────────────────────────────────────────
  //
  // The HelpMapWriter writes to the same backing Map<String, HelpTopic> that the production
  // probe targets. These tests verify that command lifecycle and help-topic lifecycle stay in
  // lockstep, which is the property a plugin developer relies on when using /help to validate
  // their plugin.yml.

  @Test
  fun `apply registers a GenericCommandHelpTopic per primary command`() {
    registrar.apply(plugin, description("fly" to mapOf<String, Any>(), "tp" to mapOf<String, Any>()))

    val flyTopic = server.helpMap.getHelpTopic("/fly")
    val tpTopic = server.helpMap.getHelpTopic("/tp")
    assertInstanceOf(GenericCommandHelpTopic::class.java, flyTopic)
    assertInstanceOf(GenericCommandHelpTopic::class.java, tpTopic)

    // Topic must point at the PluginCommand we actually registered, not a stale copy.
    val flyCmd = server.commandMap.getCommand("fly") as PluginCommand
    assertSame(flyCmd, commandOf(flyTopic as GenericCommandHelpTopic))
  }

  @Test
  fun `apply registers an alias help topic per alias`() {
    registrar.apply(
        plugin,
        description("fly" to mapOf("aliases" to listOf("flight", "f"))),
    )

    val flightTopic = server.helpMap.getHelpTopic("/flight")
    val fTopic = server.helpMap.getHelpTopic("/f")
    assertInstanceOf(PaperPlaneAliasHelpTopic::class.java, flightTopic)
    assertInstanceOf(PaperPlaneAliasHelpTopic::class.java, fTopic)
    assertTrue(flightTopic!!.shortText.contains("Alias for /fly"))
  }

  @Test
  fun `apply with empty description writes no help topics`() {
    val before = server.helpMap.helpTopics.size
    registrar.apply(plugin, description())
    assertEquals(before, server.helpMap.helpTopics.size)
    assertNull(server.helpMap.getHelpTopic("/fly"))
  }

  @Test
  fun `apply removing a command also removes its primary and alias help topics`() {
    registrar.apply(plugin, description("fly" to mapOf("aliases" to listOf("f"))))
    assertNotNull(server.helpMap.getHelpTopic("/fly"))
    assertNotNull(server.helpMap.getHelpTopic("/f"))

    registrar.apply(plugin, description())
    assertNull(server.helpMap.getHelpTopic("/fly"))
    assertNull(server.helpMap.getHelpTopic("/f"))
  }

  @Test
  fun `apply removing one alias removes only that alias's help topic`() {
    registrar.apply(
        plugin,
        description("fly" to mapOf("aliases" to listOf("flight", "f"))),
    )
    assertNotNull(server.helpMap.getHelpTopic("/flight"))
    assertNotNull(server.helpMap.getHelpTopic("/f"))

    // Changing aliases requires churning the underlying PluginCommand. Force that by removing
    // and re-adding the primary, which is what a real plugin.yml change drives.
    registrar.apply(plugin, description())
    registrar.apply(plugin, description("fly" to mapOf("aliases" to listOf("f"))))

    assertNotNull(server.helpMap.getHelpTopic("/fly"))
    assertNotNull(server.helpMap.getHelpTopic("/f"))
    assertNull(
        server.helpMap.getHelpTopic("/flight"),
        "alias 'flight' was dropped — its help topic must follow",
    )
  }

  @Test
  fun `clear removes all help topics this registrar added`() {
    registrar.apply(
        plugin,
        description(
            "fly" to mapOf("aliases" to listOf("flight")),
            "tp" to mapOf("aliases" to listOf("teleport")),
        ),
    )
    assertNotNull(server.helpMap.getHelpTopic("/fly"))
    assertNotNull(server.helpMap.getHelpTopic("/flight"))
    assertNotNull(server.helpMap.getHelpTopic("/tp"))
    assertNotNull(server.helpMap.getHelpTopic("/teleport"))

    registrar.clear()

    assertNull(server.helpMap.getHelpTopic("/fly"))
    assertNull(server.helpMap.getHelpTopic("/flight"))
    assertNull(server.helpMap.getHelpTopic("/tp"))
    assertNull(server.helpMap.getHelpTopic("/teleport"))
  }

  @Test
  fun `reload replaces help topic with the new plugin's Command reference`() {
    val v1 = MockBukkit.createMockPlugin("MyPlugin")
    val v1Registrar = CommandRegistrar(server, helpMapWriter, Logger.getLogger("v1"))
    v1Registrar.apply(v1, description("fly" to mapOf("description" to "old description")))

    val v1Topic = server.helpMap.getHelpTopic("/fly") as GenericCommandHelpTopic
    val v1Cmd = commandOf(v1Topic) as PluginCommand
    assertSame(v1, v1Cmd.plugin)

    v1Registrar.clear()

    val v2 = MockBukkit.createMockPlugin("MyPluginV2")
    val v2Registrar = CommandRegistrar(server, helpMapWriter, Logger.getLogger("v2"))
    v2Registrar.apply(v2, description("fly" to mapOf("description" to "new description")))

    val v2Topic = server.helpMap.getHelpTopic("/fly") as GenericCommandHelpTopic
    val v2Cmd = commandOf(v2Topic) as PluginCommand
    assertSame(v2, v2Cmd.plugin, "Help topic must reference the new plugin's command after reload")
    assertTrue(v1Topic !== v2Topic, "Help topic instance must be replaced on reload")
  }

  @Test
  fun `second apply with same commands leaves help topic instance unchanged`() {
    registrar.apply(plugin, description("fly" to mapOf<String, Any>()))
    val first = server.helpMap.getHelpTopic("/fly")
    registrar.apply(plugin, description("fly" to mapOf<String, Any>()))
    val second = server.helpMap.getHelpTopic("/fly")
    assertSame(
        first,
        second,
        "No-churn path must not rewrite the help topic — confirms register is gated on the same diff as commandMap.",
    )
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

  /** Reads the protected `command` field of [GenericCommandHelpTopic] for identity assertions. */
  private fun commandOf(topic: GenericCommandHelpTopic): org.bukkit.command.Command =
      GenericCommandHelpTopic::class
          .java
          .getDeclaredField("command")
          .apply { isAccessible = true }
          .get(topic) as org.bukkit.command.Command
}
