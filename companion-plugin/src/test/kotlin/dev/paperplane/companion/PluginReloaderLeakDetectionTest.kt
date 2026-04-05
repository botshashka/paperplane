package dev.paperplane.companion

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PluginReloaderLeakDetectionTest {

  private lateinit var server: ServerMock
  private lateinit var reloader: PluginReloader

  @BeforeEach
  fun setup() {
    server = MockBukkit.mock()
    reloader = PluginReloader(server, java.util.logging.Logger.getLogger("test"))
  }

  @AfterEach
  fun teardown() {
    MockBukkit.unmock()
  }

  @Test
  fun `shouldForceBlueGreen is false initially`() {
    assertFalse(reloader.shouldForceBlueGreen)
  }

  @Test
  fun `shouldForceBlueGreen triggers at threshold 3`() {
    // Use reflection to simulate consecutive leaks
    val field = PluginReloader::class.java.getDeclaredField("consecutiveLeaks")
    field.isAccessible = true

    field.setInt(reloader, 2)
    assertFalse(reloader.shouldForceBlueGreen)

    field.setInt(reloader, 3)
    assertTrue(reloader.shouldForceBlueGreen)
  }
}
