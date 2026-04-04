package dev.paperplane.companion

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PaperInternalsTest {

  private lateinit var server: ServerMock

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun tearDown() {
    PaperInternals.clearCaches()
    MockBukkit.unmock()
  }

  @Test
  fun `clearCaches does not throw`() {
    assertDoesNotThrow { PaperInternals.clearCaches() }
  }

  @Test
  fun `clearCaches can be called multiple times without error`() {
    assertDoesNotThrow {
      PaperInternals.clearCaches()
      PaperInternals.clearCaches()
      PaperInternals.clearCaches()
    }
  }

  @Test
  fun `syncCommands with MockBukkit server does not throw`() {
    assertDoesNotThrow { PaperInternals.syncCommands(server) }
  }

  @Test
  fun `registerCommands with empty commands map does not throw`() {
    val plugin = MockBukkit.createMockPlugin("TestPlugin")
    // plugin.description.commands is empty by default
    assertDoesNotThrow {
      PaperInternals.registerCommands(server, plugin, plugin.description, plugin.logger)
    }
  }

  @Test
  fun `cleanupCommands with no matching commands does not throw`() {
    val plugin = MockBukkit.createMockPlugin("TestPlugin")
    assertDoesNotThrow { PaperInternals.cleanupCommands(server, plugin, plugin.logger) }
  }
}
