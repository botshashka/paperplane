package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tee contract: every record appends one NDJSON entry preserving the raw message line verbatim —
 * the byte-faithfulness is what makes the log replayable through [CompanionWire.decode] in protocol
 * tests.
 */
class ProtocolTeeTest {

  @TempDir lateinit var serverDir: File

  private val gson = Gson()

  private fun entries(): List<JsonObject> =
      File(serverDir, ".paperplane/protocol-log.ndjson")
          .readLines()
          .filter { it.isNotBlank() }
          .map { gson.fromJson(it, JsonObject::class.java) }

  @Test
  fun `records direction, timestamp, and the raw line verbatim`() {
    val tee = ProtocolTee.forServer(serverDir)
    val raw = """{"type":"status","state":"building"}"""

    val before = System.currentTimeMillis()
    tee.record(ProtocolTee.SEND, raw)
    val after = System.currentTimeMillis()

    val entry = entries().single()
    assertEquals("send", entry.get("dir").asString)
    assertEquals(raw, entry.get("line").asString, "the raw line must be preserved byte-for-byte")
    val at = entry.get("at").asLong
    assertTrue(at in before..after, "timestamp must be the record time")
  }

  @Test
  fun `appends entries in order across directions`() {
    val tee = ProtocolTee.forServer(serverDir)
    tee.record(ProtocolTee.SEND, """{"type":"hello"}""")
    tee.record(ProtocolTee.RECV, """{"type":"welcome"}""")
    tee.record(ProtocolTee.RECV, """{"type":"ready"}""")

    val recorded = entries().map { it.get("dir").asString to it.get("line").asString }
    assertEquals(
        listOf(
            "send" to """{"type":"hello"}""",
            "recv" to """{"type":"welcome"}""",
            "recv" to """{"type":"ready"}""",
        ),
        recorded,
    )
  }

  @Test
  fun `recorded lines survive a decode round-trip`() {
    val tee = ProtocolTee.forServer(serverDir)
    tee.record(ProtocolTee.RECV, """{"type":"report","requestId":"r1","status":"ok"}""")

    val replayed = CompanionWire.decode(entries().single().get("line").asString)
    val report = (replayed as CompanionEvent.Report).report
    assertEquals("r1", report.requestId)
  }

  @Test
  fun `creates the parent directory on first record`() {
    val tee = ProtocolTee.forServer(File(serverDir, "nested"))
    tee.record(ProtocolTee.SEND, "{}")
    assertTrue(File(serverDir, "nested/.paperplane/protocol-log.ndjson").exists())
  }

  @Test
  fun `an unwritable target is swallowed so forensics can never break the protocol`() {
    // A FILE where the .paperplane directory should be makes the append fail.
    val bogusRoot = File(serverDir, "bogus").apply { writeText("not a directory") }
    val tee = ProtocolTee.forServer(bogusRoot)
    tee.record(ProtocolTee.SEND, "{}") // must not throw
  }
}
