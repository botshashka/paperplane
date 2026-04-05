package dev.paperplane.cli.devserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class BlueGreenModeTest {

  // ── Slot enum ─────────────────────────────────────────────────────

  @Test
  fun `SERVER slot has correct name and port`() {
    assertEquals("server", BlueGreenMode.Slot.SERVER.serverName)
    assertEquals(DevSession.SERVER_PORT, BlueGreenMode.Slot.SERVER.port)
  }

  @Test
  fun `SWAP slot has correct name and port`() {
    assertEquals("swap", BlueGreenMode.Slot.SWAP.serverName)
    assertEquals(DevSession.SWAP_PORT, BlueGreenMode.Slot.SWAP.port)
  }

  @Test
  fun `SERVER and SWAP have different ports`() {
    assertNotEquals(BlueGreenMode.Slot.SERVER.port, BlueGreenMode.Slot.SWAP.port)
  }

  @Test
  fun `other() returns opposite slot`() {
    assertEquals(BlueGreenMode.Slot.SWAP, BlueGreenMode.Slot.SERVER.other())
    assertEquals(BlueGreenMode.Slot.SERVER, BlueGreenMode.Slot.SWAP.other())
  }

  @Test
  fun `other() is its own inverse`() {
    for (slot in BlueGreenMode.Slot.entries) {
      assertEquals(slot, slot.other().other())
    }
  }

  @Test
  fun `enum has exactly two slots`() {
    assertEquals(2, BlueGreenMode.Slot.entries.size)
  }
}
