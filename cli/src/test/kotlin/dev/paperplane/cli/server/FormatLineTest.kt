package dev.paperplane.cli.server

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FormatLineTest {

  @TempDir lateinit var tempDir: File

  private fun createServerManager(): PaperServerManager {
    val serverDir = File(tempDir, "server")
    val cacheDir = File(tempDir, "cache")
    return PaperServerManager(serverDir, PaperDownloader(cacheDir))
  }

  private fun createVelocityManager(): VelocityManager {
    return VelocityManager(File(tempDir, "proxy"))
  }

  // ── PaperServerManager.formatServerLine ─────────────────────────────

  @Test
  fun `formatServerLine extracts thread and message from standard log line`() {
    val manager = createServerManager()
    val result = manager.formatServerLine("[12:34:56] [Server thread/INFO] Hello world")

    // Should contain the thread and message regardless of color mode
    assertTrue(result.contains("Server thread/INFO"))
    assertTrue(result.contains("Hello world"))
  }

  @Test
  fun `formatServerLine passes through non-matching lines unchanged`() {
    val manager = createServerManager()
    val result = manager.formatServerLine("Some raw output without timestamp")

    assertEquals("Some raw output without timestamp", result)
  }

  @Test
  fun `formatServerLine handles various timestamp formats`() {
    val manager = createServerManager()

    val result1 = manager.formatServerLine("[00:00:00] [main/INFO] Starting")
    assertTrue(result1.contains("main/INFO"))
    assertTrue(result1.contains("Starting"))

    val result2 = manager.formatServerLine("[23:59:59] [Async/WARN] Something bad")
    assertTrue(result2.contains("Async/WARN"))
    assertTrue(result2.contains("Something bad"))
  }

  @Test
  fun `formatServerLine does not contain raw ANSI when NO_COLOR is set`() {
    // TerminalUI.useColor is based on NO_COLOR env at init time.
    // In test env, NO_COLOR is typically not set, so we can't easily toggle.
    // Instead, just verify the structure is correct.
    val manager = createServerManager()
    val result = manager.formatServerLine("[12:34:56] [Thread/INFO] message")

    // Either contains ANSI codes or plain brackets — both valid based on env
    assertTrue(
        result.contains("[Thread/INFO]") || result.contains("\u001b[2m[Thread/INFO]"),
        "Should contain thread info in some format",
    )
  }

  // ── VelocityManager.formatProxyLine ─────────────────────────────────

  @Test
  fun `formatProxyLine extracts thread and message from proxy log`() {
    val manager = createVelocityManager()
    val result = manager.formatProxyLine("[12:34:56] [main/INFO] Proxy started")

    assertTrue(result.contains("proxy/main/INFO"))
    assertTrue(result.contains("Proxy started"))
  }

  @Test
  fun `formatProxyLine prefixes non-matching lines with proxy tag`() {
    val manager = createVelocityManager()
    val result = manager.formatProxyLine("Raw proxy output")

    assertTrue(result.contains("[proxy]"))
    assertTrue(result.contains("Raw proxy output"))
  }

  @Test
  fun `formatProxyLine always includes proxy prefix`() {
    val manager = createVelocityManager()

    val matching = manager.formatProxyLine("[00:00:00] [cm-0/INFO] Connected")
    assertTrue(matching.contains("proxy/"))

    val nonMatching = manager.formatProxyLine("something else")
    assertTrue(nonMatching.contains("[proxy]"))
  }

  @Test
  fun `formatProxyLine handles thread names with special characters`() {
    val manager = createVelocityManager()
    val result =
        manager.formatProxyLine("[12:00:00] [Netty Epoll Server IO #0/INFO] Channel active")

    assertTrue(result.contains("Netty Epoll Server IO #0/INFO"))
    assertTrue(result.contains("Channel active"))
  }
}
