package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadStatus
import dev.paperplane.cli.devserver.ReloadStrategy
import dev.paperplane.cli.devserver.instant.RedefineCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wire-codec contract: encoded CLI→companion lines carry the shapes the companion's mirrored codec
 * parses, and companion→CLI lines decode into the right [CompanionEvent]s. Unknown/malformed input
 * must decode to null (skip), never throw — the reader loop's resilience depends on it.
 */
class CompanionWireTest {

  private val gson = Gson()

  private fun parse(line: String): JsonObject = gson.fromJson(line, JsonObject::class.java)

  // ── Encoding ────────────────────────────────────────────────────────

  @Test
  fun `encodeHello carries type, token, and protocol version`() {
    val obj = parse(CompanionWire.encodeHello("secret-token"))
    assertEquals("hello", obj.get("type").asString)
    assertEquals("secret-token", obj.get("token").asString)
    assertEquals(CompanionSocketFile.PROTOCOL_VERSION, obj.get("protocolVersion").asInt)
  }

  @Test
  fun `encodeStatus carries state and optional extras`() {
    val obj = parse(CompanionWire.encodeStatus("ready", "1.2s", null))
    assertEquals("status", obj.get("type").asString)
    assertEquals("ready", obj.get("state").asString)
    assertEquals("1.2s", obj.get("duration").asString)
    assertFalse(obj.has("message"), "absent extras must be omitted, not null-serialized")
  }

  @Test
  fun `encodeStatus with a message carries it`() {
    val obj = parse(CompanionWire.encodeStatus("error", null, "Build failed"))
    assertEquals("Build failed", obj.get("message").asString)
    assertFalse(obj.has("duration"))
  }

  @Test
  fun `encodeLoad flattens the request fields beside the type tag`() {
    val request =
        LoadRequest(
            requestId = "r1",
            jarPath = "/x.jar",
            pluginName = "Sample",
            classesDirs = listOf("/c"),
            resourcesDir = "/r",
            runtimeClasspath = listOf("/lib.jar"),
            leakDiagnostics = "full",
        )

    val obj = parse(CompanionWire.encodeLoad(request))

    assertEquals("load", obj.get("type").asString)
    assertEquals("r1", obj.get("requestId").asString)
    assertEquals("/x.jar", obj.get("jarPath").asString)
    assertEquals("Sample", obj.get("pluginName").asString)
    assertEquals("/c", obj.getAsJsonArray("classesDirs").single().asString)
    assertEquals("/r", obj.get("resourcesDir").asString)
    assertEquals("/lib.jar", obj.getAsJsonArray("runtimeClasspath").single().asString)
    assertEquals("full", obj.get("leakDiagnostics").asString)
  }

  @Test
  fun `encodeInstantSwap flattens the request beside the type tag`() {
    val request =
        dev.paperplane.cli.devserver.InstantSwapRequest(
            requestId = "i1",
            pluginName = "Sample",
            classes =
                listOf(
                    dev.paperplane.cli.devserver.InstantClassEntry(
                        fqcn = "com.example.Foo",
                        expectedCrc32 = 42L,
                        data = "QUJD",
                    )
                ),
            newClasses =
                listOf(
                    dev.paperplane.cli.devserver.InstantClassEntry(
                        fqcn = "com.example.New",
                        data = "REVG",
                    )
                ),
        )

    val obj = parse(CompanionWire.encodeInstantSwap(request))

    assertEquals("instantSwap", obj.get("type").asString)
    assertEquals("i1", obj.get("requestId").asString)
    assertEquals("Sample", obj.get("pluginName").asString)
    val entry = obj.getAsJsonArray("classes").single().asJsonObject
    assertEquals("com.example.Foo", entry.get("fqcn").asString)
    assertEquals(42L, entry.get("expectedCrc32").asLong)
    assertEquals("QUJD", entry.get("data").asString)
    val newEntry = obj.getAsJsonArray("newClasses").single().asJsonObject
    assertEquals("com.example.New", newEntry.get("fqcn").asString)
    assertEquals(0L, newEntry.get("expectedCrc32").asLong)
  }

  @Test
  fun `decodes an instantReport into the typed event`() {
    val event =
        CompanionWire.decode(
            """{"type":"instantReport","requestId":"i1","status":"ok","patched":2,""" +
                """"defined":1,"durationMs":12}"""
        )

    val report = (event as CompanionEvent.InstantReport).report
    assertEquals("i1", report.requestId)
    assertEquals(dev.paperplane.cli.devserver.InstantSwapStatus.OK, report.status)
    assertEquals(2, report.patched)
    assertEquals(1, report.defined)
    assertEquals(12L, report.durationMs)
  }

  @Test
  fun `decodes a refused instantReport with its reason`() {
    val event =
        CompanionWire.decode(
            """{"type":"instantReport","requestId":"i2","status":"refused",""" +
                """"reason":"baseline drift on com.example.Foo"}"""
        )

    val report = (event as CompanionEvent.InstantReport).report
    assertEquals(dev.paperplane.cli.devserver.InstantSwapStatus.REFUSED, report.status)
    assertEquals("baseline drift on com.example.Foo", report.reason)
  }

  @Test
  fun `decodes the welcome capability and degrades absent or unknown values to NONE`() {
    val bodyOnly =
        CompanionWire.decode(
            """{"type":"welcome","protocolVersion":4,"serverReady":true,""" +
                """"capability":"body-only"}"""
        ) as CompanionEvent.Welcome
    assertEquals(RedefineCapability.BODY_ONLY, bodyOnly.capability)

    val additive =
        CompanionWire.decode(
            """{"type":"welcome","protocolVersion":4,"serverReady":true,""" +
                """"capability":"additive"}"""
        ) as CompanionEvent.Welcome
    assertEquals(RedefineCapability.ADDITIVE, additive.capability)

    val absent =
        CompanionWire.decode("""{"type":"welcome","protocolVersion":4,"serverReady":false}""")
            as CompanionEvent.Welcome
    assertEquals(RedefineCapability.NONE, absent.capability)

    // A companion advertising a tier this CLI doesn't know must degrade to "can't patch",
    // never to a tier it can't honour.
    val unknown =
        CompanionWire.decode(
            """{"type":"welcome","protocolVersion":4,"serverReady":true,""" +
                """"capability":"quantum"}"""
        ) as CompanionEvent.Welcome
    assertEquals(RedefineCapability.NONE, unknown.capability)
  }

  @Test
  fun `encoded messages are single lines`() {
    val lines =
        listOf(
            CompanionWire.encodeHello("t"),
            CompanionWire.encodeStatus("building", null, null),
            CompanionWire.encodeLoad(LoadRequest("r", "/j", "P")),
        )
    for (line in lines) {
      assertFalse(line.contains("\n"), "NDJSON framing requires single-line messages: $line")
    }
  }

  // ── Decoding ────────────────────────────────────────────────────────

  @Test
  fun `decodes a welcome with the readiness snapshot`() {
    val event =
        CompanionWire.decode("""{"type":"welcome","protocolVersion":3,"serverReady":true}""")
    val welcome = assertInstanceOf(CompanionEvent.Welcome::class.java, event)
    assertEquals(3, welcome.protocolVersion)
    assertTrue(welcome.serverReady)
  }

  @Test
  fun `a welcome with missing fields decodes with safe defaults`() {
    // A degenerate welcome must not throw; the client's version check rejects it downstream.
    val welcome =
        assertInstanceOf(
            CompanionEvent.Welcome::class.java,
            CompanionWire.decode("""{"type":"welcome"}"""),
        )
    assertEquals(0, welcome.protocolVersion)
    assertFalse(welcome.serverReady)
  }

  @Test
  fun `decodes ready and saveComplete`() {
    assertEquals(CompanionEvent.Ready, CompanionWire.decode("""{"type":"ready"}"""))
    assertEquals(CompanionEvent.SaveComplete, CompanionWire.decode("""{"type":"saveComplete"}"""))
  }

  @Test
  fun `decodes a loadProgress stage`() {
    val event =
        CompanionWire.decode("""{"type":"loadProgress","requestId":"r1","stage":"loading"}""")
    assertEquals(CompanionEvent.LoadProgress("r1", "loading"), event)
  }

  @Test
  fun `decodes a full report with typed status and strategy`() {
    val event =
        CompanionWire.decode(
            """{"type":"report","requestId":"r1","status":"ok","strategy":"reload",""" +
                """"durationMs":42,"pluginName":"Sample","leaks":{"consecutive":1,""" +
                """"confirmedSurvivors":2,"attribution":[{"kind":"thread","detail":"timer"}]},""" +
                """"action":"restart"}"""
        )

    val report = assertInstanceOf(CompanionEvent.Report::class.java, event).report
    assertEquals("r1", report.requestId)
    assertEquals(LoadStatus.OK, report.status)
    assertEquals(ReloadStrategy.RELOAD, report.strategy)
    assertEquals(42, report.durationMs)
    assertEquals("Sample", report.pluginName)
    assertEquals(1, report.leaks!!.consecutive)
    assertEquals("timer", report.leaks!!.attribution.single().detail)
    assertEquals("restart", report.action)
  }

  @Test
  fun `decodes a failed report`() {
    val event =
        CompanionWire.decode(
            """{"type":"report","requestId":"r1","status":"failed","strategy":"fresh",""" +
                """"durationMs":0,"pluginName":"Sample","message":"plugin.yml not found"}"""
        )
    val report = assertInstanceOf(CompanionEvent.Report::class.java, event).report
    assertEquals(LoadStatus.FAILED, report.status)
    assertEquals(ReloadStrategy.FRESH, report.strategy)
    assertEquals("plugin.yml not found", report.message)
  }

  @Test
  fun `a report with unknown enum values decodes with null status`() {
    // Forward-compat: a newer companion emitting a new strategy must not throw; the null status
    // is treated as a failure by the waiter rather than crashing the reader.
    val event =
        CompanionWire.decode(
            """{"type":"report","requestId":"r1","status":"maybe","strategy":"quantum"}"""
        )
    val report = assertInstanceOf(CompanionEvent.Report::class.java, event).report
    assertNull(report.status)
    assertNull(report.strategy)
  }

  @Test
  fun `unknown types, missing types, and malformed json decode to null`() {
    assertNull(CompanionWire.decode("""{"type":"mystery"}"""))
    assertNull(CompanionWire.decode("""{"state":"building"}"""))
    assertNull(CompanionWire.decode("{not json"))
    assertNull(CompanionWire.decode(""))
    assertNull(CompanionWire.decode("""{"type":42}"""))
  }
}
