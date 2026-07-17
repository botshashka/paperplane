package dev.paperplane.cli.server

import com.google.gson.Gson
import dev.paperplane.cli.Versions
import dev.paperplane.cli.testing.LocalHttpServer
import dev.paperplane.cli.testing.readTestResource
import java.net.http.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperVersionResolverTest {

  private lateinit var http: LocalHttpServer
  private lateinit var client: HttpClient
  private val gson = Gson()

  @BeforeEach
  fun setUp() {
    http = LocalHttpServer()
    client = HttpClient.newHttpClient()
  }

  @AfterEach
  fun tearDown() {
    http.stop()
  }

  private fun resolver(): PaperVersionResolver = PaperVersionResolver(client, http.baseUrl)

  /** Builds a Fill v3-shaped project response (versions grouped by minor line) from a flat list. */
  private fun respondVersions(vararg versions: String) {
    val grouped = versions.groupBy { it.split(".").take(2).joinToString(".") }
    http.serveText("/", gson.toJson(mapOf("versions" to grouped)))
  }

  // ── real captured Fill v3 responses (golden) ──────────────────────

  @Test
  fun `resolveLatest parses real Fill v3 project response shape`() {
    http.serveText("/", readTestResource("/fill/paper-project.json"))

    // Latest supported stable line in the captured payload.
    assertEquals("1.21.11", resolver().resolveLatest())
  }

  @Test
  fun `resolveRecent parses real Fill v3 project response shape`() {
    http.serveText("/", readTestResource("/fill/paper-project.json"))

    assertEquals(listOf("1.21.9", "1.21.10", "1.21.11"), resolver().resolveRecent(3))
  }

  // ── resolveLatest ─────────────────────────────────────────────────

  @Test
  fun `resolveLatest returns latest version within supported range`() {
    respondVersions(
        "1.18",
        "1.18.2",
        "1.19",
        "1.19.4",
        "1.20",
        "1.20.6",
        "1.21",
        "1.21.4",
        "1.21.10",
    )

    assertEquals("1.21.10", resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest normalizes descending API ordering`() {
    // Fill v3 lists newest-first within each group; the resolver must still sort ascending.
    respondVersions("1.21.10", "1.21.4", "1.21", "1.20.6", "1.20")

    assertEquals("1.21.10", resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest filters out unsupported versions`() {
    respondVersions("1.18", "1.21.4", "1.22", "1.22.1")

    // 1.22.x is not in SUPPORTED_API_VERSIONS, so latest supported is 1.21.4
    assertEquals("1.21.4", resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback when no versions in supported range`() {
    respondVersions("1.22", "1.23")

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback on non-200 response`() {
    http.serveStatus("/", 500)

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback on connection failure`() {
    // Stop the server to simulate connection failure
    http.stop()

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback on malformed JSON`() {
    http.serveText("/", "not json")

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest filters out pre-release versions`() {
    respondVersions("1.21.4", "1.21.10", "1.21.11-pre1", "1.21.11-rc1")

    assertEquals("1.21.10", resolver().resolveLatest())
  }

  // ── resolveRecent ─────────────────────────────────────────────────

  @Test
  fun `resolveRecent returns last N supported versions`() {
    respondVersions(
        "1.18",
        "1.18.2",
        "1.19",
        "1.19.4",
        "1.20",
        "1.20.6",
        "1.21",
        "1.21.4",
        "1.21.10",
    )

    assertEquals(listOf("1.21", "1.21.4", "1.21.10"), resolver().resolveRecent(3))
  }

  @Test
  fun `resolveRecent respects count parameter`() {
    respondVersions(
        "1.18",
        "1.18.2",
        "1.19",
        "1.19.4",
        "1.20",
        "1.20.6",
        "1.21",
        "1.21.4",
        "1.21.10",
    )

    assertEquals(listOf("1.21.4", "1.21.10"), resolver().resolveRecent(2))
  }

  @Test
  fun `resolveRecent filters out unsupported versions`() {
    respondVersions("1.18", "1.21.4", "1.22", "1.22.1")

    assertEquals(listOf("1.18", "1.21.4"), resolver().resolveRecent(3))
  }

  @Test
  fun `resolveRecent returns fallback list when no supported versions`() {
    respondVersions("1.22", "1.23")

    assertEquals(listOf(Versions.PAPER_FALLBACK), resolver().resolveRecent())
  }

  @Test
  fun `resolveRecent returns fallback list on connection failure`() {
    http.stop()

    assertEquals(listOf(Versions.PAPER_FALLBACK), resolver().resolveRecent())
  }

  @Test
  fun `resolveRecent returns fallback list on non-200 response`() {
    http.serveStatus("/", 500)

    assertEquals(listOf(Versions.PAPER_FALLBACK), resolver().resolveRecent())
  }

  @Test
  fun `resolveRecent filters out pre-release versions`() {
    respondVersions("1.21", "1.21.4", "1.21.10", "1.21.11-pre1", "1.21.11-rc1", "1.21.11-rc2")

    assertEquals(listOf("1.21", "1.21.4", "1.21.10"), resolver().resolveRecent(3))
  }

  @Test
  fun `resolveRecent returns all when fewer than count`() {
    respondVersions("1.21.4", "1.21.10")

    assertEquals(listOf("1.21.4", "1.21.10"), resolver().resolveRecent(5))
  }
}
