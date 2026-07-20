package dev.paperplane.cli.ipc

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Handshake-file contract: parse the companion-written shape, reject everything that could send the
 * dial loop to a bogus port (missing, torn, portless, tokenless), and never throw — a torn read
 * must just mean "retry next poll".
 */
class CompanionSocketFileTest {

  @TempDir lateinit var serverDir: File

  private fun write(content: String) {
    CompanionSocketFile.path(serverDir).apply { parentFile.mkdirs() }.writeText(content)
  }

  @Test
  fun `reads the companion-written shape`() {
    write("""{"port":43155,"token":"abc123","protocolVersion":3}""")

    val info = CompanionSocketFile.read(serverDir)!!

    assertEquals(43155, info.port)
    assertEquals("abc123", info.token)
    assertEquals(3, info.protocolVersion)
  }

  @Test
  fun `missing file reads as null`() {
    assertNull(CompanionSocketFile.read(serverDir))
  }

  @Test
  fun `torn or malformed json reads as null instead of throwing`() {
    write("""{"port":431""")
    assertNull(CompanionSocketFile.read(serverDir), "a mid-write read must mean retry, not crash")
  }

  @Test
  fun `a file without a usable port is rejected`() {
    write("""{"token":"abc"}""")
    assertNull(CompanionSocketFile.read(serverDir))
    write("""{"port":0,"token":"abc"}""")
    assertNull(CompanionSocketFile.read(serverDir))
    write("""{"port":70000,"token":"abc"}""")
    assertNull(CompanionSocketFile.read(serverDir))
  }

  @Test
  fun `a file without a token is rejected`() {
    write("""{"port":43155}""")
    assertNull(CompanionSocketFile.read(serverDir), "tokenless files can't authenticate — reject")
  }

  @Test
  fun `empty file reads as null`() {
    write("")
    assertNull(CompanionSocketFile.read(serverDir))
  }

  @Test
  fun `delete removes the file and tolerates absence`() {
    write("""{"port":43155,"token":"abc","protocolVersion":3}""")
    CompanionSocketFile.delete(serverDir)
    assertFalse(CompanionSocketFile.path(serverDir).exists())
    CompanionSocketFile.delete(serverDir) // idempotent
  }
}
