package dev.paperplane.cli.devserver

import com.google.gson.Gson
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Contract tests for [PollingLoadResultWaiter]: it must match the result to the request it wrote
 * (so a stale result from a prior reload is never mistaken for this one's), degrade gracefully on
 * torn/plain-text content, and abort early when the server process dies.
 */
class LoadResultWaiterTest {

  @TempDir lateinit var serverDir: File

  private val terminal = RecordingTerminal()
  private val waiter = PollingLoadResultWaiter(TerminalUI(terminal))
  private val gson = Gson()

  private fun await(expectedRequestId: String, timeoutMs: Long, alive: Boolean = true) =
      waiter.await(serverDir, expectedRequestId, timeoutMs) { alive }

  private fun writeComplete(report: LoadReport) =
      LoadRequest.completeFlag(serverDir)
          .apply { parentFile.mkdirs() }
          .writeText(gson.toJson(report))

  private fun writeFailed(report: LoadReport) =
      LoadRequest.failedFlag(serverDir).apply { parentFile.mkdirs() }.writeText(gson.toJson(report))

  private fun assertNoClaimLeftovers() {
    val leftovers =
        File(serverDir, ".paperplane").listFiles().orEmpty().filter { it.name.endsWith(".claim") }
    assertTrue(leftovers.isEmpty(), "claim files must be consumed, found: $leftovers")
  }

  // ── Happy paths ─────────────────────────────────────────────────────

  @Test
  fun `matching complete report resolves to Ok`() {
    writeComplete(LoadReport(requestId = "r1", status = "ok", strategy = "fresh", pluginName = "P"))

    val result = await("r1", timeoutMs = 1000)

    val ok = assertInstanceOf(LoadWaitResult.Ok::class.java, result)
    assertEquals("r1", ok.report!!.requestId)
    assertEquals("fresh", ok.report!!.strategy)
    assertNoClaimLeftovers()
  }

  @Test
  fun `matching failed report resolves to Failed with the message`() {
    writeFailed(LoadReport(requestId = "r1", status = "failed", message = "plugin.yml not found"))

    val result = await("r1", timeoutMs = 1000)

    val failed = assertInstanceOf(LoadWaitResult.Failed::class.java, result)
    assertEquals("plugin.yml not found", failed.message)
    assertEquals("r1", failed.report!!.requestId)
    assertNoClaimLeftovers()
  }

  // ── requestId filtering ─────────────────────────────────────────────

  @Test
  fun `complete result with a mismatched requestId is dropped and the wait times out`() {
    writeComplete(LoadReport(requestId = "OLD", status = "ok", pluginName = "P"))

    val result = await("NEW", timeoutMs = 300)

    assertEquals(LoadWaitResult.TimedOut, result)
    assertFalse(
        LoadRequest.completeFlag(serverDir).exists(),
        "stale result must be deleted so it can't be re-read forever",
    )
    assertNoClaimLeftovers()
  }

  @Test
  fun `failed result with a mismatched requestId is dropped and the wait times out`() {
    writeFailed(LoadReport(requestId = "OLD", status = "failed", message = "old failure"))

    val result = await("NEW", timeoutMs = 300)

    assertEquals(LoadWaitResult.TimedOut, result)
    assertFalse(
        LoadRequest.failedFlag(serverDir).exists(),
        "stale result must be deleted so it can't be re-read forever",
    )
    assertNoClaimLeftovers()
  }

  // ── Graceful degradation on torn / plain-text content ───────────────

  @Test
  fun `unparseable complete flag degrades to Ok with a null report and a warning`() {
    LoadRequest.completeFlag(serverDir).apply { parentFile.mkdirs() }.writeText("not json {")

    val result = await("r1", timeoutMs = 1000)

    val ok = assertInstanceOf(LoadWaitResult.Ok::class.java, result)
    assertNull(ok.report, "torn success flag has no parsed report")
    assertTrue(
        terminal.writes.any { it.contains("not valid JSON") },
        "degradation must be visible, not silent",
    )
  }

  @Test
  fun `unparseable failed flag degrades to Failed carrying the raw text`() {
    LoadRequest.failedFlag(serverDir)
        .apply { parentFile.mkdirs() }
        .writeText("legacy plain-text error")

    val result = await("r1", timeoutMs = 1000)

    val failed = assertInstanceOf(LoadWaitResult.Failed::class.java, result)
    assertEquals("legacy plain-text error", failed.message)
    assertNull(failed.report)
    assertTrue(
        terminal.writes.any { it.contains("not valid JSON") },
        "degradation must be visible, not silent",
    )
  }

  @Test
  fun `empty complete flag degrades to Ok with a null report`() {
    LoadRequest.completeFlag(serverDir).apply { parentFile.mkdirs() }.writeText("")

    val result = await("r1", timeoutMs = 1000)

    val ok = assertInstanceOf(LoadWaitResult.Ok::class.java, result)
    assertNull(ok.report)
  }

  // ── Server death ────────────────────────────────────────────────────

  @Test
  fun `dead server aborts the wait immediately with ServerExited`() {
    val start = System.currentTimeMillis()
    val result = await("r1", timeoutMs = 10_000, alive = false)
    val elapsed = System.currentTimeMillis() - start

    assertEquals(LoadWaitResult.ServerExited, result)
    assertTrue(elapsed < 5_000, "must not wait out the timeout on a dead server, took ${elapsed}ms")
  }

  // ── Timeout ─────────────────────────────────────────────────────────

  @Test
  fun `no flag within the timeout resolves to TimedOut`() {
    val start = System.currentTimeMillis()
    val result = await("r1", timeoutMs = 250)
    val elapsed = System.currentTimeMillis() - start

    assertEquals(LoadWaitResult.TimedOut, result)
    assertTrue(elapsed >= 200, "should wait close to the timeout, waited ${elapsed}ms")
  }
}
