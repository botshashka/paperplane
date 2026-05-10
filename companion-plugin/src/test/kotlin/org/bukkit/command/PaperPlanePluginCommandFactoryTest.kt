package org.bukkit.command

import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/**
 * Pinpoint the package-access trick: [PaperPlanePluginCommandFactory] must be in the
 * `org.bukkit.command` package so it can call `PluginCommand`'s package-private constructor
 * without reflection. If a future paper-api ever makes the constructor private, this test fails
 * at compile time. If it changes the constructor signature, this test fails at runtime.
 */
class PaperPlanePluginCommandFactoryTest {

  private lateinit var plugin: JavaPlugin

  @BeforeEach
  fun setUp() {
    MockBukkit.mock()
    plugin = MockBukkit.createMockPlugin("Test")
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  @Test
  fun `create returns a PluginCommand bound to the given owner`() {
    val cmd = PaperPlanePluginCommandFactory.create("fly", plugin)
    assertNotNull(cmd)
    assertEquals("fly", cmd.name)
    assertSame(plugin, cmd.plugin, "owner must be the plugin we passed")
  }

  @Test
  fun `create with different names yields independent commands`() {
    val a = PaperPlanePluginCommandFactory.create("a", plugin)
    val b = PaperPlanePluginCommandFactory.create("b", plugin)
    assertEquals("a", a.name)
    assertEquals("b", b.name)
    // Identity check: distinct instances, not a cached singleton.
    assert(a !== b)
  }

  @Test
  fun `created command exposes mutable description and aliases (hosts depend on these)`() {
    val cmd = PaperPlanePluginCommandFactory.create("fly", plugin)
    cmd.description = "Toggle flight"
    cmd.aliases = listOf("flight", "f")
    cmd.permission = "myplugin.fly"
    assertEquals("Toggle flight", cmd.description)
    assertEquals(listOf("flight", "f"), cmd.aliases)
    assertEquals("myplugin.fly", cmd.permission)
  }
}
