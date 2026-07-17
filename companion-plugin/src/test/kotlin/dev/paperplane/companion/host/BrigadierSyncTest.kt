package dev.paperplane.companion.host

import org.bukkit.Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class BrigadierSyncTest {

  private lateinit var server: ServerMock

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  /**
   * A [Server] whose runtime class exposes a public no-arg `syncCommands()` — the CraftServer
   * method [BrigadierSync] resolves reflectively (it isn't on the Bukkit `Server` interface).
   * Delegates through a `Server`-typed reference so interface delegation binds to `Server`'s
   * signatures.
   */
  class SyncingServer(delegate: Server) : Server by delegate {
    var syncCount = 0

    fun syncCommands() {
      syncCount++
    }
  }

  @Test
  fun `sync invokes syncCommands when the server exposes it`() {
    val base: Server = server
    val syncing = SyncingServer(base)

    BrigadierSync.sync(syncing)

    assertEquals(1, syncing.syncCount, "syncCommands must be invoked exactly once")
  }

  @Test
  fun `sync swallows the miss when the server has no syncCommands`() {
    // ServerMock's runtime class has no syncCommands() — getMethod throws NoSuchMethodException,
    // which must be swallowed rather than propagated (a missed refresh must never break the host).
    assertDoesNotThrow { BrigadierSync.sync(server) }
  }
}
