package dev.paperplane.companion.host

import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PermissionRegistrarTest {

  private lateinit var server: ServerMock
  private lateinit var registrar: PermissionRegistrar

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    registrar = PermissionRegistrar(server)
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  // ── happy path ──────────────────────────────────────────────────────

  @Test
  fun `apply registers permissions`() {
    val description = description("my.fly" to "Allows flight", "my.tp" to "Allows teleport")
    registrar.apply(description)

    assertEquals(setOf("my.fly", "my.tp"), registrar.registered())
    assertNotNull(server.pluginManager.getPermission("my.fly"))
    assertEquals("Allows flight", server.pluginManager.getPermission("my.fly")!!.description)
  }

  @Test
  fun `apply with no permissions leaves registered empty`() {
    registrar.apply(description())
    assertTrue(registrar.registered().isEmpty())
  }

  // ── diff: add, remove, no-churn ─────────────────────────────────────

  @Test
  fun `apply removing a permission unregisters it`() {
    registrar.apply(description("my.fly" to "fly", "my.tp" to "tp"))
    registrar.apply(description("my.fly" to "fly"))
    assertEquals(setOf("my.fly"), registrar.registered())
    assertNull(server.pluginManager.getPermission("my.tp"))
  }

  @Test
  fun `apply adding a permission registers it`() {
    registrar.apply(description("my.fly" to "fly"))
    registrar.apply(description("my.fly" to "fly", "my.tp" to "tp"))
    assertEquals(setOf("my.fly", "my.tp"), registrar.registered())
    assertNotNull(server.pluginManager.getPermission("my.tp"))
  }

  @Test
  fun `apply with same permissions twice does not re-register`() {
    val description = description("my.fly" to "fly")
    registrar.apply(description)
    val first = server.pluginManager.getPermission("my.fly")
    registrar.apply(description)
    val second = server.pluginManager.getPermission("my.fly")
    assertTrue(
        first === second,
        "same spec must not cause re-registration (identity preserved across applies).",
    )
  }

  // ── clear ───────────────────────────────────────────────────────────

  @Test
  fun `clear removes all registered permissions`() {
    registrar.apply(description("my.fly" to "fly", "my.tp" to "tp"))
    registrar.clear()
    assertTrue(registrar.registered().isEmpty())
    assertNull(server.pluginManager.getPermission("my.fly"))
    assertNull(server.pluginManager.getPermission("my.tp"))
  }

  @Test
  fun `clear is idempotent`() {
    registrar.apply(description("my.fly" to "fly"))
    registrar.clear()
    registrar.clear()
    assertTrue(registrar.registered().isEmpty())
  }

  // ── permission default ──────────────────────────────────────────────

  @Test
  fun `apply preserves permission default`() {
    val yaml = buildString {
      appendLine("name: MyPlugin")
      appendLine("main: com.example.MyPlugin")
      appendLine("version: 1.0")
      appendLine("permissions:")
      appendLine("  my.public:")
      appendLine("    description: Available to everyone")
      appendLine("    default: true")
      appendLine("  my.opOnly:")
      appendLine("    description: Op-only")
      appendLine("    default: op")
    }
    registrar.apply(PluginDescriptionFile(yaml.byteInputStream()))

    val pub = server.pluginManager.getPermission("my.public")!!
    val opOnly = server.pluginManager.getPermission("my.opOnly")!!
    assertEquals(PermissionDefault.TRUE, pub.default)
    assertEquals(PermissionDefault.OP, opOnly.default)
  }

  // ── re-register on spec change ──────────────────────────────────────

  @Test
  fun `apply with same name but different description re-registers`() {
    val first =
        PluginDescriptionFile(
            ("name: MyPlugin\nmain: x\nversion: 1.0\npermissions:\n" +
                    "  my.fly:\n    description: Old description")
                .byteInputStream(),
        )
    val second =
        PluginDescriptionFile(
            ("name: MyPlugin\nmain: x\nversion: 1.0\npermissions:\n" +
                    "  my.fly:\n    description: New description")
                .byteInputStream(),
        )
    registrar.apply(first)
    val v1 = server.pluginManager.getPermission("my.fly")!!
    assertEquals("Old description", v1.description)

    registrar.apply(second)
    val v2 = server.pluginManager.getPermission("my.fly")!!
    assertEquals("New description", v2.description)
    assert(v1 !== v2) { "Description change must replace the Permission instance" }
  }

  @Test
  fun `clear after apply leaves pluginManager with no leftover permissions`() {
    registrar.apply(description("a" to "A", "b" to "B", "c" to "C"))
    assertEquals(3, registrar.registered().size)
    registrar.clear()
    assertNull(server.pluginManager.getPermission("a"))
    assertNull(server.pluginManager.getPermission("b"))
    assertNull(server.pluginManager.getPermission("c"))
  }

  // ── children cascade ────────────────────────────────────────────────

  @Test
  fun `apply registers parent permission with children mapping`() {
    val yaml = buildString {
      appendLine("name: MyPlugin")
      appendLine("main: com.example.MyPlugin")
      appendLine("version: 1.0")
      appendLine("permissions:")
      appendLine("  my.admin:")
      appendLine("    description: Admin")
      appendLine("    children:")
      appendLine("      my.fly: true")
      appendLine("      my.tp: true")
    }
    registrar.apply(PluginDescriptionFile(yaml.byteInputStream()))

    val admin = server.pluginManager.getPermission("my.admin")!!
    assertEquals(true, admin.children["my.fly"])
    assertEquals(true, admin.children["my.tp"])
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun description(vararg perms: Pair<String, String>): PluginDescriptionFile {
    val yaml = buildString {
      appendLine("name: MyPlugin")
      appendLine("main: com.example.MyPlugin")
      appendLine("version: 1.0")
      if (perms.isNotEmpty()) {
        appendLine("permissions:")
        for ((name, desc) in perms) {
          appendLine("  $name:")
          appendLine("    description: $desc")
        }
      }
    }
    return PluginDescriptionFile(yaml.byteInputStream())
  }
}
