package dev.paperplane.cli.server

import dev.paperplane.cli.testing.LocalHttpServer
import dev.paperplane.cli.testing.readTestResource
import java.io.File
import java.io.IOException
import java.net.http.HttpClient
import java.security.MessageDigest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FillClientTest {

  private lateinit var http: LocalHttpServer
  private lateinit var client: HttpClient

  @TempDir lateinit var cacheDir: File

  @BeforeEach
  fun setUp() {
    http = LocalHttpServer()
    client = HttpClient.newHttpClient()
  }

  @AfterEach
  fun tearDown() {
    http.stop()
  }

  private fun fill() = FillClient(client, "Paper")

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  // ── golden: real captured Fill v3 responses (source of truth) ──────

  @Test
  fun `parses real Fill v3 paper builds-latest response shape`() {
    val jar = fill().parseLatestServerJar(readTestResource("/fill/paper-1.21.4-builds-latest.json"))

    assertEquals("paper-1.21.4-232.jar", jar.name)
    assertEquals(
        "https://fill-data.papermc.io/v1/objects/" +
            "5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc/paper-1.21.4-232.jar",
        jar.url,
    )
    assertEquals("5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc", jar.sha256)
    assertEquals(51437498L, jar.size)
  }

  @Test
  fun `parses real Fill v3 velocity builds-latest response shape`() {
    val jar =
        fill().parseLatestServerJar(readTestResource("/fill/velocity-3.4.0-builds-latest.json"))

    assertEquals("velocity-3.4.0-566.jar", jar.name)
    assertEquals(
        "https://fill-data.papermc.io/v1/objects/" +
            "fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e/velocity-3.4.0-566.jar",
        jar.url,
    )
    assertEquals("fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e", jar.sha256)
    assertEquals(17816748L, jar.size)
  }

  @Test
  fun `parseVersionsMap flattens the real Fill v3 project response grouped, newest-first`() {
    val versions = fill().parseVersionsMap(readTestResource("/fill/paper-project.json"))

    // Grouped map keyed by line, newest line first; flatten preserves that order.
    assertEquals("26.2", versions.first())
    assertTrue(versions.contains("1.21.11"))
    assertTrue(versions.contains("1.18"))
  }

  // ── fetch ──────────────────────────────────────────────────────────

  @Test
  fun `fetch throws on non-200`() {
    http.serveStatus("/meta", 500)

    assertThrows(IOException::class.java) { fill().fetch("${http.baseUrl}/meta") }
  }

  // ── downloadVerified integrity ─────────────────────────────────────

  @Test
  fun `downloadVerified writes the jar when size and checksum match`() {
    val bytes = "PAPER-JAR-BYTES".toByteArray()
    http.serveBytes("/jar", bytes)
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar", sha256(bytes), bytes.size.toLong())

    val out = fill().downloadVerified(jar, cacheDir, "paper-1.21.4.jar")

    assertEquals(File(cacheDir, "paper-1.21.4.jar"), out)
    assertEquals("PAPER-JAR-BYTES", out.readText())
    assertFalse(File(cacheDir, ".paper-1.21.4.jar.tmp").exists())
  }

  @Test
  fun `downloadVerified skips verification when the build advertises no size or checksum`() {
    val bytes = "NO-METADATA".toByteArray()
    http.serveBytes("/jar", bytes)
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar")

    val out = fill().downloadVerified(jar, cacheDir, "paper-1.21.4.jar")

    assertEquals("NO-METADATA", out.readText())
  }

  @Test
  fun `downloadVerified rejects a size mismatch and leaves no jar behind`() {
    val bytes = "TRUNCATED-BY-PROXY".toByteArray()
    http.serveBytes("/jar", bytes)
    // Advertise a larger size than the body actually served — a silent 200 corruption.
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar", sha256(bytes), bytes.size + 100L)

    assertThrows(IOException::class.java) {
      fill().downloadVerified(jar, cacheDir, "paper-1.21.4.jar")
    }
    assertFalse(File(cacheDir, "paper-1.21.4.jar").exists())
    assertFalse(File(cacheDir, ".paper-1.21.4.jar.tmp").exists())
  }

  @Test
  fun `downloadVerified rejects a checksum mismatch and leaves no jar behind`() {
    val bytes = "CORRUPTED".toByteArray()
    http.serveBytes("/jar", bytes)
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar", "0".repeat(64), bytes.size.toLong())

    assertThrows(IOException::class.java) {
      fill().downloadVerified(jar, cacheDir, "paper-1.21.4.jar")
    }
    assertFalse(File(cacheDir, "paper-1.21.4.jar").exists())
    assertFalse(File(cacheDir, ".paper-1.21.4.jar.tmp").exists())
  }

  @Test
  fun `downloadVerified leaves no jar behind when the transfer drops mid-stream`() {
    // Declare 4096 bytes but write only 5 before closing → the client read fails mid-body.
    http.serveTruncated("/jar", 4096L, ByteArray(5))
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar", "abc", 4096L)

    assertThrows(IOException::class.java) {
      fill().downloadVerified(jar, cacheDir, "paper-1.21.4.jar")
    }
    assertFalse(File(cacheDir, "paper-1.21.4.jar").exists())
    assertFalse(File(cacheDir, ".paper-1.21.4.jar.tmp").exists())
  }

  @Test
  fun `downloadVerified throws and leaves no jar behind on a non-200 download`() {
    http.serveStatus("/jar", 404)
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar")

    assertThrows(IOException::class.java) {
      fill().downloadVerified(jar, cacheDir, "paper-1.21.4.jar")
    }
    assertFalse(File(cacheDir, "paper-1.21.4.jar").exists())
    assertFalse(File(cacheDir, ".paper-1.21.4.jar.tmp").exists())
  }

  @Test
  fun `downloadVerified creates the cache dir when it does not yet exist`() {
    val nested = File(cacheDir, "nested/cache")
    val bytes = "jar".toByteArray()
    http.serveBytes("/jar", bytes)
    val jar = ServerJar("paper.jar", "${http.baseUrl}/jar", sha256(bytes), bytes.size.toLong())

    val out = FillClient(client, "Paper").downloadVerified(jar, nested, "paper-1.21.4.jar")

    assertTrue(out.exists())
  }
}
