package dev.paperplane.companion.host

import org.bukkit.command.CommandSender
import org.bukkit.help.GenericCommandHelpTopic
import org.bukkit.help.HelpTopic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PaperPlaneAliasHelpTopicTest {

  private lateinit var server: ServerMock

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  @Test
  fun `getFullText delegates to the primary topic`() {
    val plugin = MockBukkit.createMockPlugin("MyPlugin")
    val primary = StubFullTextTopic("/fly", "Toggle flight on/off")
    server.helpMap.addTopic(primary)
    val alias = PaperPlaneAliasHelpTopic("/flight", "/fly", server.helpMap)

    val sender = server.consoleSender
    val full = alias.getFullText(sender)
    assertTrue(full.contains("Alias for /fly"), "Alias short text must precede delegated body")
    assertTrue(full.contains("Toggle flight on/off"), "Full text must include the primary's body")
    // Plugin reference keeps MockBukkit from GC'ing the mock plugin while the test runs.
    assertEquals("MyPlugin", plugin.name)
  }

  @Test
  fun `getFullText degrades to shortText when primary topic is missing`() {
    val alias = PaperPlaneAliasHelpTopic("/flight", "/fly", server.helpMap)
    val out = alias.getFullText(server.consoleSender)
    assertEquals("Alias for /fly", out)
  }

  @Test
  fun `canSee delegates to the primary topic when no permission amendment`() {
    val primary = StubCanSeeTopic("/fly", canSee = false)
    server.helpMap.addTopic(primary)
    val alias = PaperPlaneAliasHelpTopic("/flight", "/fly", server.helpMap)

    assertFalse(
        alias.canSee(server.consoleSender),
        "Alias must hide itself when the primary hides itself",
    )
  }

  @Test
  fun `canSee returns false when primary topic is missing and no amendment`() {
    val alias = PaperPlaneAliasHelpTopic("/flight", "/gone", server.helpMap)
    assertFalse(alias.canSee(server.consoleSender))
  }

  @Test
  fun `canSee uses amendedPermission when set, bypassing delegation`() {
    val primary = StubCanSeeTopic("/fly", canSee = false)
    server.helpMap.addTopic(primary)
    val alias = PaperPlaneAliasHelpTopic("/flight", "/fly", server.helpMap)
    alias.amendCanSee("myplugin.flight")

    val player = server.addPlayer()
    player.addAttachment(MockBukkit.createMockPlugin("perm"), "myplugin.flight", true)
    assertTrue(
        alias.canSee(player),
        "amendedPermission must take precedence over the primary's canSee",
    )
  }

  @Test
  fun `constructor rejects self-alias`() {
    assertThrows(IllegalArgumentException::class.java) {
      PaperPlaneAliasHelpTopic("/fly", "/fly", server.helpMap)
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private class StubFullTextTopic(topicName: String, body: String) : HelpTopic() {
    init {
      name = topicName
      shortText = body
      fullText = body
    }

    override fun canSee(player: CommandSender): Boolean = true
  }

  private class StubCanSeeTopic(topicName: String, private val canSee: Boolean) : HelpTopic() {
    init {
      name = topicName
      shortText = ""
    }

    override fun canSee(player: CommandSender): Boolean = canSee
  }

  // Reference HelpTopic + GenericCommandHelpTopic so unused-import-tools don't drop them.
  @Suppress("unused") private val keep: Class<*> = GenericCommandHelpTopic::class.java
}
