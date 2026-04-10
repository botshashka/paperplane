package dev.paperplane.cli.plugins

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the real [ModrinthClient] against an in-process [HttpServer] that serves synthetic
 * Modrinth-shaped JSON. Verifies the parsing edges that matter most: primary-file selection, hash
 * extraction, version filtering, the Paper-loader query string, and the 404 → [ModrinthNotFound]
 * mapping.
 */
class ModrinthClientTest {

  private lateinit var server: HttpServer
  private var lastRequestPath: String = ""
  private lateinit var client: ModrinthClient

  @BeforeEach
  fun setUp() {
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.start()
    val baseUrl = "http://localhost:${server.address.port}"
    client = ModrinthClient(HttpClient.newHttpClient(), baseUrl)
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  /** Convenience: register a handler that records the path and returns the given JSON body. */
  private fun respondWith(path: String, json: String) {
    server.createContext(path) { exchange: HttpExchange ->
      lastRequestPath = exchange.requestURI.toString()
      val bytes = json.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  private fun respondNotFound(path: String) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(404, -1)
      exchange.close()
    }
  }

  @Test
  fun `resolveLatest picks newest version and primary file`() {
    val json =
        """
        [
          {
            "version_number": "2.11.6",
            "files": [
              {"primary": false, "url": "https://cdn/api-sources.jar", "filename": "src.jar",
               "hashes": {"sha1": "deadbeef", "sha512": "0011"}},
              {"primary": true, "url": "https://cdn/PlaceholderAPI-2.11.6.jar",
               "filename": "PlaceholderAPI-2.11.6.jar", "hashes": {"sha1": "deadbeef", "sha512": "abcd"}}
            ]
          },
          {
            "version_number": "2.11.5",
            "files": [
              {"primary": true, "url": "https://cdn/older.jar", "filename": "older.jar",
               "hashes": {"sha1": "deadbeef", "sha512": "ffff"}}
            ]
          }
        ]
        """
            .trimIndent()
    respondWith("/project/placeholderapi/version", json)

    val resolved = client.resolveLatest("placeholderapi", "1.21.4")
    assertNotNull(resolved)
    assertEquals("placeholderapi", resolved!!.slug)
    assertEquals("2.11.6", resolved.versionNumber)
    assertEquals("https://cdn/PlaceholderAPI-2.11.6.jar", resolved.downloadUrl)
    assertEquals("abcd", resolved.sha512)
    assertEquals("PlaceholderAPI-2.11.6.jar", resolved.filename)
  }

  @Test
  fun `resolveLatest passes paper loader and game version filters`() {
    respondWith("/project/x/version", "[]")
    client.resolveLatest("x", "1.21.4")
    assertTrue(lastRequestPath.contains("loaders="), "expected loaders filter in: $lastRequestPath")
    assertTrue(lastRequestPath.contains("game_versions="), "expected game_versions filter")
    // URL-encoded JSON arrays — checking the literal substrings is enough.
    assertTrue(lastRequestPath.contains("paper"), "expected paper loader: $lastRequestPath")
    assertTrue(lastRequestPath.contains("1.21.4"), "expected mc version: $lastRequestPath")
  }

  @Test
  fun `resolveLatest returns null when project has no compatible versions`() {
    respondWith("/project/empty/version", "[]")
    assertNull(client.resolveLatest("empty", "1.21.4"))
  }

  @Test
  fun `resolveExact matches by version_number string`() {
    val json =
        """
        [
          {"version_number": "1.8.0", "files": [{"primary": true, "url": "u8", "filename": "8.jar",
           "hashes": {"sha1": "deadbeef", "sha512": "h8"}}]},
          {"version_number": "1.7.3", "files": [{"primary": true, "url": "u7", "filename": "7.jar",
           "hashes": {"sha1": "deadbeef", "sha512": "h7"}}]}
        ]
        """
            .trimIndent()
    respondWith("/project/vault/version", json)

    val pinned = client.resolveExact("vault", "1.7.3", "1.21.4")
    assertNotNull(pinned)
    assertEquals("1.7.3", pinned!!.versionNumber)
    assertEquals("h7", pinned.sha512)
  }

  @Test
  fun `resolveExact returns null when version doesn't match`() {
    respondWith(
        "/project/vault/version",
        """[{"version_number": "1.8.0", "files":
           [{"primary": true, "url": "u", "filename": "f", "hashes": {"sha1": "deadbeef", "sha512": "h"}}]}]""",
    )
    assertNull(client.resolveExact("vault", "9.9.9", "1.21.4"))
  }

  @Test
  fun `falls back to first file when none flagged primary`() {
    val json =
        """
        [{"version_number": "1.0.0", "files": [
          {"url": "first", "filename": "first.jar", "hashes": {"sha1": "deadbeef", "sha512": "h1"}},
          {"url": "second", "filename": "second.jar", "hashes": {"sha1": "deadbeef", "sha512": "h2"}}
        ]}]
        """
            .trimIndent()
    respondWith("/project/x/version", json)
    val resolved = client.resolveLatest("x", "1.21.4")!!
    assertEquals("first", resolved.downloadUrl)
    assertEquals("h1", resolved.sha512)
  }

  @Test
  fun `skips entries missing required fields`() {
    val json =
        """
        [
          {"version_number": "1.0.0", "files": []},
          {"version_number": "2.0.0", "files":
            [{"primary": true, "url": "ok", "filename": "ok.jar", "hashes": {"sha1": "deadbeef", "sha512": "ok"}}]}
        ]
        """
            .trimIndent()
    respondWith("/project/x/version", json)
    val resolved = client.listCompatibleVersions("x", "1.21.4")
    // First entry has empty files array → toResolvedVersion returns null → filtered out by
    // mapNotNull.
    assertEquals(1, resolved.size)
    assertEquals("2.0.0", resolved[0].versionNumber)
  }

  @Test
  fun `404 throws ModrinthNotFound`() {
    respondNotFound("/project/missing/version")
    assertThrows(ModrinthNotFound::class.java) { client.resolveLatest("missing", "1.21.4") }
  }

  @Test
  fun `5xx throws IOException`() {
    server.createContext("/project/broken/version") { exchange ->
      exchange.sendResponseHeaders(500, -1)
      exchange.close()
    }
    assertThrows(java.io.IOException::class.java) { client.resolveLatest("broken", "1.21.4") }
  }

  @Test
  fun `getProject resolves real Modrinth project shape from an ID`() {
    // Captured from `curl https://api.modrinth.com/v2/project/1u6JkXh5` (WorldEdit). Modrinth's
    // /project endpoint accepts both slug and 8-char project ID interchangeably and always
    // returns the canonical slug in the body. AddPluginCommand uses this to rewrite IDs to
    // slugs before saving, so paperplane.yml never contains opaque IDs.
    val json =
        """
        {
          "id": "1u6JkXh5",
          "slug": "worldedit",
          "title": "WorldEdit",
          "description": "...",
          "project_type": "plugin"
        }
        """
            .trimIndent()
    respondWith("/project/1u6JkXh5", json)
    val info = client.getProject("1u6JkXh5")
    assertEquals("worldedit", info.slug)
    assertEquals("WorldEdit", info.title)
    assertEquals("1u6JkXh5", info.id)
  }

  @Test
  fun `getProject 404 throws ModrinthNotFound`() {
    respondNotFound("/project/nonexistent")
    assertThrows(ModrinthNotFound::class.java) { client.getProject("nonexistent") }
  }

  @Test
  fun `parses real Modrinth response shape with sha1 and sha512 only`() {
    // Captured from `curl https://api.modrinth.com/v2/project/placeholderapi/version` —
    // verbatim Modrinth output, NOT a hand-rolled fixture. The hashes block contains sha1 and
    // sha512 only; sha256 is not present. An earlier version of this parser required sha256
    // and silently filtered every entry to null, breaking real-world `ppl plugin add` calls.
    // Locking in the real shape with this golden test ensures we never regress.
    val json =
        """
        [{
          "id": "UmbIiI5H",
          "project_id": "lKEzGugV",
          "name": "PlaceholderAPI 2.12.2",
          "version_number": "2.12.2",
          "loaders": ["bukkit","paper","purpur","spigot"],
          "game_versions": ["1.21.10","1.21.11"],
          "files": [{
            "hashes": {
              "sha1": "c4de624e5445f4ff2a09a3f133e99f0153921e15",
              "sha512": "94addf996ba45e16dbded3fcaf05e8b442212ce0d577f7edc42b743ad9532c1e24115263976126d36f27c0868ab1c03c40c2d13947985124b92dabca4527dddb"
            },
            "url": "https://cdn.modrinth.com/data/lKEzGugV/versions/UmbIiI5H/PlaceholderAPI-2.12.2.jar",
            "filename": "PlaceholderAPI-2.12.2.jar",
            "primary": true,
            "size": 1154620,
            "file_type": null
          }]
        }]
        """
            .trimIndent()
    respondWith("/project/placeholderapi/version", json)

    val resolved = client.resolveLatest("placeholderapi", "1.21.11")
    assertNotNull(resolved, "real Modrinth shape must parse without sha256")
    assertEquals("2.12.2", resolved!!.versionNumber)
    assertEquals("PlaceholderAPI-2.12.2.jar", resolved.filename)
    val expectedSha =
        "94addf996ba45e16dbded3fcaf05e8b442212ce0d577f7edc42b743ad9532c1e" +
            "24115263976126d36f27c0868ab1c03c40c2d13947985124b92dabca4527dddb"
    assertEquals(expectedSha, resolved.sha512)
  }

  @Test
  fun `connection failure throws ModrinthNetworkError`() {
    val deadClient =
        ModrinthClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:1",
        ) // unlikely-to-be-bound port
    assertThrows(ModrinthNetworkError::class.java) {
      deadClient.resolveLatest("anything", "1.21.4")
    }
  }
}
