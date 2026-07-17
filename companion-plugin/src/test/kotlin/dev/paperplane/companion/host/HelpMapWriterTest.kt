package dev.paperplane.companion.host

import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.help.GenericCommandHelpTopic
import org.bukkit.help.HelpTopic
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Unit tests for [HelpMapWriter], focused on the invariant that a hot-reload cycle must not destroy
 * a foreign command's `/help` entry when an inner-plugin command's name (or alias) collides with
 * it.
 */
class HelpMapWriterTest {

  private lateinit var server: ServerMock
  private lateinit var plugin: Plugin
  private lateinit var topics: MutableMap<String, HelpTopic>
  private lateinit var writer: HelpMapWriter

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    plugin = MockBukkit.createMockPlugin("MyPlugin")
    topics = mutableMapOf()
    writer = HelpMapWriter(server.helpMap, topics)
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  private fun pluginCommand(name: String, aliases: List<String> = emptyList()): PluginCommand {
    val ctor =
        PluginCommand::class.java.getDeclaredConstructor(String::class.java, Plugin::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(name, plugin).apply { this.aliases = aliases }
  }

  private class ForeignTopic(topicName: String) : HelpTopic() {
    init {
      name = topicName
      shortText = "foreign"
    }

    override fun getFullText(forWho: CommandSender): String = shortText

    override fun canSee(player: CommandSender): Boolean = true
  }

  @Test
  fun `register displaces a colliding foreign topic and unregister restores it`() {
    val foreign = ForeignTopic("/fly")
    topics["/fly"] = foreign
    val cmd = pluginCommand("fly")

    writer.register(cmd)
    assertInstanceOf(
        GenericCommandHelpTopic::class.java,
        topics["/fly"],
        "our topic must take over the colliding key while the plugin is loaded",
    )

    writer.unregister(cmd)
    assertSame(
        foreign,
        topics["/fly"],
        "the pre-existing foreign topic must be restored on teardown, not destroyed",
    )
  }

  @Test
  fun `register displaces and restores a colliding foreign alias topic`() {
    val foreign = ForeignTopic("/f")
    topics["/f"] = foreign
    val cmd = pluginCommand("fly", aliases = listOf("f"))

    writer.register(cmd)
    assertFalse(topics["/f"] === foreign, "alias key must be taken over while loaded")

    writer.unregister(cmd)
    assertSame(foreign, topics["/f"], "foreign alias topic must be restored on teardown")
  }

  @Test
  fun `register without a prior topic is fully removed on unregister`() {
    val cmd = pluginCommand("tp", aliases = listOf("teleport"))

    writer.register(cmd)
    assertTrue(topics.containsKey("/tp"))
    assertTrue(topics.containsKey("/teleport"))

    writer.unregister(cmd)
    assertFalse(topics.containsKey("/tp"), "an un-displaced topic must be removed cleanly")
    assertFalse(topics.containsKey("/teleport"))
  }

  @Test
  fun `an alias equal to the primary name is not written as a separate alias topic`() {
    val cmd = pluginCommand("fly", aliases = listOf("fly"))

    writer.register(cmd)
    assertInstanceOf(
        GenericCommandHelpTopic::class.java,
        topics["/fly"],
        "the primary topic must not be clobbered by an identically-named alias",
    )

    writer.unregister(cmd)
    assertFalse(topics.containsKey("/fly"))
  }
}
